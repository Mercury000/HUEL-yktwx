package com.mercury.yktwx.data

import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Common fee protocol; only observed payment branches are accepted. */
class FeeApi(openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) : FeeService {
    private val http = PaymentHttp(openConnection)
    private suspend fun get(path: String, token: String, params: Map<String, String>) =
        http.json(http.request(path, token, params, chargeClient = true), feePayment = true)
    private suspend fun post(path: String, token: String, fields: Map<String, String>, sign: Boolean = false) =
        http.json(http.request(path, token, form = if (sign) RechargeProtocol.signed(fields) else fields, chargeClient = true), feePayment = true)

    override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
        val response = get("/charge/feeitem/singleFeeitem", token, mapOf("feeitemid" to project.id))
        val item = response.getJSONObject("feeitem")
        if (item.rechargeText("feeitemid") != project.id) throw YktException("缴费项目不匹配")
        val min = item.rechargeLimit("retain_money")
        val max = item.rechargeLimit("maxmoney")
        val flag = item.rechargeText("flag").getOrNull(7)
        val options = item.rechargeText("layout").split(',').mapNotNull { RechargeMoney.cents(it.trim()) }
            .distinct().filter { (min == null || it >= min) && (max == null || it <= max) }
        if (min != null && max != null && min > max || (flag == '1' && options.isEmpty()))
            throw YktException("没有可用的缴费金额")
        val rules = RechargeConfiguration(project.id, "", options, min, max, flag != '1', flag == '2')
        val enabled = !project.network && item.optInt("bindStatus") == 1 &&
            item.optJSONObject("feeitemImplBean")?.optInt("bindinfo") == 1
        val scene = response.rechargeText("sceneinfo")
        val binding = if (enabled) FeeRoomBinding.parse(scene) else null
        return FeeConfiguration(project, rules,
            enabled, binding, enabled && scene.isNotBlank() && binding == null)
    }

    override suspend fun setFeeRoom(token: String, project: FeeProject, room: FeeRoomBinding?) {
        if (project.network) throw YktException("当前项目不支持房间绑定")
        val body = JSONObject().put("feeitemid", project.id.toInt())
        room?.let { body.put("sceneinfo", it.sceneInfo()) }
        http.json(http.request("/charge/sceneBind/add", token, chargeClient = true, jsonBody = body))
    }

    override suspend fun feeQuery(token: String, project: FeeProject, level: Int, selections: Map<String, String>, detail: Boolean): FeeQuery {
        val map = post("/charge/feeitem/getThirdData", token, selections + mapOf(
            "feeitemid" to project.id, "type" to if (detail) "IEC" else "select", "level" to level.toString(),
        )).getJSONObject("map")
        val total = map.optJSONArray("total")
        val levels = (0 until (total?.length() ?: 0)).mapNotNull {
            val l = total!!.getJSONObject(it)
            val key = l.rechargeText("code")
            if (key.isBlank()) null else FeeLevel(key, l.optInt("level"), l.rechargeText("name"))
        }.sortedBy { it.number }
        val options = map.optJSONArray("data")
        val items = (0 until (options?.length() ?: 0)).map {
            val o = options!!.getJSONObject(it); FeeOption(o.rechargeText("value"), o.rechargeText("name"))
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }
        if (levels.any { !it.key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) || it.number !in 1..8 } ||
            levels.map { it.key }.distinct().size != levels.size || levels.map { it.number }.distinct().size != levels.size)
            throw YktException("缴费查询层级配置异常")
        val display = map.optJSONObject("showData")?.let { d -> d.keys().asSequence().map { it to d.rechargeText(it) }.toList() }.orEmpty()
        val target = if (detail) map.optJSONObject("data")?.takeIf { it.length() > 0 }?.withRoomInfo()?.toString() else null
        val tip = map.rechargeText("tipinfo").ifBlank { null }
        return FeeQuery(levels, items, target, display, tip, tip == null && !map.optBoolean("notPay", false))
    }

    /** The old frontend adds this client-side; the server derives the bill abstract from it. */
    private fun JSONObject.withRoomInfo(): JSONObject {
        if (optString("myCustomInfo").isNotBlank()) return this
        val names = listOf("areaName", "buildingName", "floorName", "roomName").map { optString(it) }
            .takeIf { parts -> parts.all { it.isNotBlank() } }
            ?: listOf("xiaoqu_id", "loudong_id", "louceng_id", "room_id").map { optString(it).substringAfter('&', "") }
        if (names.all { it.isNotBlank() }) put("myCustomInfo", "房间：" + names.joinToString(" "))
        return this
    }

    override suspend fun createFee(token: String, username: String, config: FeeConfiguration, query: FeeQuery, amountCents: Long): FeeOrder {
        config.amountRules.amountError(amountCents)?.let { throw YktException(it) }
        if (!query.canPay) throw YktException("当前目标不可缴费")
        val target = query.thirdParty ?: throw YktException("请先查询缴费目标")
        val data = post("/blade-pay/pay", token, mapOf(
            "feeitemid" to config.project.id, "tranamt" to RechargeMoney.yuan(amountCents), "flag" to "choose",
            "source" to "app", "paystep" to "0", "abstracts" to "", "redirect_url" to RechargeProtocol.SCHOOL + "/plat",
            "third_party" to target,
        ), sign = true).getJSONObject("data")
        val id = data.rechargeText("orderid")
        if (!RechargeProtocol.validId(id)) throw YktException("订单创建响应异常")
        val pending = PendingRecharge(username, target, config.project.id, amountCents, id)
        val info = orderInfo(token, pending)
        val methods = info.optJSONArray("payList") ?: throw YktException("没有可用的支付方式")
        val supported = (0 until methods.length()).map { methods.getJSONObject(it) }.filter {
            it.rechargeText("code") in setOf("ACCOUNTTSM", "TSM") && !it.optBoolean("chooseAccount", false)
        }.map {
            FeeMethod(it.rechargeText("payid"), it.rechargeText("code"), if (it.rechargeText("code") == "ACCOUNTTSM") "一卡通" else "微信",
                it.optBoolean("chooseAccount", false), it.optInt("nopassword", 0))
        }.filter { it.id.matches(Regex("[0-9]{1,12}")) }
        if (supported.isEmpty()) throw YktException("没有受支持的支付方式，请在官网处理订单")
        return FeeOrder(pending, supported)
    }

    private suspend fun orderInfo(token: String, order: PendingRecharge): JSONObject {
        val payload = get("/charge/pay/getpayinfo", token, mapOf("orderid" to (order.orderId ?: throw YktException("订单号缺失"))))
        val o = payload.getJSONObject("order")
        verifyOrder(o, order, strict = true, verifyPayId = false)
        return payload
    }
    private fun payFields(order: PendingRecharge, method: FeeMethod) = mapOf(
        "orderid" to (order.orderId ?: throw YktException("订单号缺失")), "paytype" to method.code, "paytypeid" to method.id, "paystep" to "2",
    )
    private suspend fun verifyMethod(token: String, order: PendingRecharge, method: FeeMethod) {
        val info = orderInfo(token, order)
        requireUnpaid(info.getJSONObject("order"))
        val methods = info.optJSONArray("payList") ?: throw YktException("支付方式不可用")
        val selected = (0 until methods.length()).map { methods.getJSONObject(it) }.firstOrNull {
            it.rechargeText("payid") == method.id && it.rechargeText("code") == method.code && !it.optBoolean("chooseAccount", false)
        } ?: throw YktException("支付方式已变化，请在官网处理")
        if (!method.wechat && (selected.optInt("nopassword", 0) != method.noPassword ||
            selected.optJSONArray("tradVerificationCombox")?.let { it.length() > 0 && it.getJSONObject(0).optInt("key", -1) != 0 } == true))
            throw YktException("支付验证方式已变化，请使用官网缴费")
        requirePayable(token, info.getJSONObject("order"))
    }

    private fun requireUnpaid(order: JSONObject) {
        when (order.optInt("status", -1)) {
            1 -> throw FeeOrderAlreadyPaid()
            2 -> throw FeeOrderExpired()
            0 -> Unit
            else -> throw YktException("订单状态已变化，请在官网核实")
        }
    }
    private suspend fun requirePayable(token: String, order: JSONObject) {
        val current = get("/charge/order/getCurrentTime", token, emptyMap()).rechargeText("currentTime")
        FeeOrderValidity.requireCurrent(order.rechargeText("payexpdate"), current)
    }

    override suspend fun prepareFeeAccount(token: String, order: PendingRecharge, method: FeeMethod): FeeAccountPreparation {
        if (method.code != "ACCOUNTTSM" || method.chooseAccount) throw YktException("暂不支持该账户支付方式")
        verifyMethod(token, order, method)
        val data = post("/blade-pay/pay", token, payFields(order, method), sign = true).getJSONObject("data")
        val array = data.optJSONArray("ccctype") ?: throw YktException("没有可支付电子账户")
        val returnedOrder = data.rechargeText("orderid")
        if (returnedOrder.isNotBlank() && returnedOrder != order.orderId) throw YktException("支付键盘订单不匹配")
        val accounts = (0 until array.length()).map {
            val a = array.getJSONObject(it)
            FeeAccount(a.rechargeText("ccctype"), a.rechargeText("name").ifBlank { "统一账户" }, RechargeMoney.responseCents(a.opt("balance")))
        }.filter { it.type.matches(Regex("[A-Za-z0-9_-]{1,64}")) }.distinctBy { it.type }
        val keyboard = data.optJSONObject("passwordMap") ?: throw YktException("支付键盘不可用，请使用官网缴费")
        if (accounts.isEmpty() || keyboard.length() != 1) throw YktException("支付账户或键盘异常")
        val uuid = keyboard.keys().next()
        val digits = keyboard.rechargeText(uuid)
        if (!uuid.matches(Regex("[A-Za-z0-9_-]{1,128}")) || digits.length != 10 || digits.toSet().size != 10 || digits.any { it !in '0'..'9' }) throw YktException("支付键盘异常")
        return FeeAccountPreparation(order.orderId ?: throw YktException("订单号缺失"), method.id, accounts, uuid, digits)
    }

    override suspend fun payFeeAccount(token: String, order: PendingRecharge, method: FeeMethod, preparation: FeeAccountPreparation, type: String, password: String) {
        if (method.code != "ACCOUNTTSM" || method.chooseAccount || preparation.orderId != order.orderId || preparation.methodId != method.id || preparation.accounts.none { it.type == type })
            throw YktException("支付账户不匹配")
        // Everything in this block is read-only. A failure here cannot have submitted the password.
        val passwordFields = preparation.paymentFields(password)
        try {
            verifyMethod(token, order, method)
        } catch (e: CancellationException) { throw e }
        catch (e: FeeOrderExpired) { throw e }
        catch (e: FeeOrderAlreadyPaid) { throw e }
        catch (e: AuthenticationRequired) { throw e }
        catch (e: YktException) { throw FeePaymentNotStarted(e.message ?: "支付检查未完成，请稍后重试") }
        catch (_: Exception) { throw FeePaymentNotStarted("支付检查未完成，请稍后重试") }
        // Displayed balance may be stale; let the backend choose rejection priority (e.g. lost card).
        // Only this request may debit the account; never retry it automatically.
        val payload = post("/blade-pay/pay", token, payFields(order, method) + mapOf("ccctype" to type, "isWX" to "0") + passwordFields, sign = true)
        if (payload.opt("data") != order.orderId) throw YktException("支付响应异常")
    }

    override suspend fun launchFeeWechat(token: String, order: PendingRecharge, method: FeeMethod): WechatRechargeLaunch {
        if (!method.wechat) throw YktException("支付方式不匹配")
        verifyMethod(token, order, method)
        val data = post("/blade-pay/pay", token, payFields(order, method) + ("isWX" to "0"), sign = true).getJSONObject("data")
        if (data.rechargeText("orderid") != order.orderId) throw YktException("支付订单不匹配")
        val linked = order.withOrder(order.orderId, method.id)
        return http.wechatLaunch(data.rechargeText("paysubmit"), linked, feePayment = true)
    }

    override suspend fun feeResult(token: String, order: PendingRecharge): RechargeResult {
        val payload = get("/charge/order/personal_data", token, buildMap {
            put("orderid", order.orderId ?: throw YktException("订单号缺失")); order.payId?.let { put("payid", it) }
        })
        val list = payload.optJSONArray("orderList") ?: throw YktException("订单查询响应异常")
        if (list.length() == 0) return RechargeResult(false, 0)
        if (list.length() != 1) throw YktException("订单结果异常")
        val o = list.getJSONObject(0)
        val status = o.rechargeText("status").toIntOrNull() ?: -1
        verifyOrder(o, order, strict = status == 1, verifyPayId = true)
        if (status != 1) return RechargeResult(false, status, (o.opt("msg") as? String)?.takeIf { it.isNotEmpty() })
        if (RechargeMoney.responseCents(o.opt("actulamt")) != order.amountCents) throw YktException("实付金额不匹配")
        return RechargeResult(true, status)
    }
    private fun verifyOrder(o: JSONObject, order: PendingRecharge, strict: Boolean, verifyPayId: Boolean) {
        if (o.rechargeText("orderid") != order.orderId) throw YktException("缴费订单不匹配")
        val target = o.rechargeText("third_party")
        val targetMatches = if (!strict && target.isBlank()) true else runCatching { JSONObject(target).jsonTargetEquals(JSONObject(order.targetId)) }.getOrDefault(false)
        val amount = o.rechargeText("tranamt")
        val amountMatches = (!strict && amount.isBlank()) || RechargeMoney.responseCents(o.opt("tranamt")) == order.amountCents
        val fees = o.optJSONArray("feeitemlist")
        val feeMatches = (!strict && (o.isNull("feeitemlist") || fees?.length() == 0)) ||
            (fees != null && (0 until fees.length()).any { fees.getJSONObject(it).rechargeText("feeitemid") == order.feeItemId })
        val payId = o.rechargeText("payid")
        val payMatches = !verifyPayId || order.payId == null || (!strict && payId.isBlank()) || payId == order.payId
        if (!targetMatches || !amountMatches || !feeMatches || !payMatches) throw YktException("缴费目标或金额不匹配")
    }
}
