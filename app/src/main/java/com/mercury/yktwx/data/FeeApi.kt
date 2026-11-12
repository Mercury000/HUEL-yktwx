package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call

/** Common fee protocol; only observed payment branches are accepted. */
class FeeApi(client: Call.Factory) : FeeService {
    private val http = PaymentHttp(client)
    private suspend fun get(path: String, token: String, params: Map<String, String>) =
        http.json(http.request(path, token, params, chargeClient = true), feePayment = true)
    private suspend fun post(path: String, token: String, fields: Map<String, String>, sign: Boolean = false) =
        http.json(http.request(path, token, form = if (sign) RechargeProtocol.signed(fields) else fields, chargeClient = true), feePayment = true)

    // Include JSON decoding and option conversion in the background work, not just HTTP I/O.
    override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration = withContext(Dispatchers.Default) {
        val response = get("/charge/feeitem/singleFeeitem", token, mapOf("feeitemid" to project.id))
        val item = response.objectValue("feeitem")
        if (item.rechargeText("feeitemid") != project.id) throw YktException("缴费项目不匹配")
        val min = item.rechargeLimit("retain_money")
        val max = item.rechargeLimit("maxmoney")
        val flag = item.rechargeText("flag").getOrNull(7)
        val options = item.rechargeText("layout").split(',').mapNotNull { RechargeMoney.cents(it.trim()) }
            .distinct().filter { (min == null || it >= min) && (max == null || it <= max) }
        if (min != null && max != null && min > max || (flag == '1' && options.isEmpty()))
            throw YktException("没有可用的缴费金额")
        val rules = RechargeConfiguration(project.id, "", options, min, max, flag != '1', flag == '2')
        val enabled = !project.network && item.intOrDefault("bindStatus") == 1 &&
            item.objectOrNull("feeitemImplBean")?.intOrDefault("bindinfo") == 1
        val scene = response.rechargeText("sceneinfo")
        val binding = if (enabled) FeeRoomBinding.parse(scene) else null
        FeeConfiguration(project, rules,
            enabled, binding, enabled && scene.isNotBlank() && binding == null)
    }

    override suspend fun setFeeRoom(token: String, project: FeeProject, room: FeeRoomBinding?) {
        if (project.network) throw YktException("当前项目不支持房间绑定")
        val body = jsonObjectOf("feeitemid" to project.id.toInt(), "sceneinfo" to room?.sceneInfo())
        http.json(http.request("/charge/sceneBind/add", token, chargeClient = true, jsonBody = body))
    }

    override suspend fun feeQuery(token: String, project: FeeProject, level: Int, selections: Map<String, String>, detail: Boolean): FeeQuery = withContext(Dispatchers.Default) {
        val map = post("/charge/feeitem/getThirdData", token, selections + mapOf(
            "feeitemid" to project.id, "type" to if (detail) "IEC" else "select", "level" to level.toString(),
        )).objectValue("map")
        val total = map.arrayOrNull("total")
        val levels = (0 until (total?.size ?: 0)).mapNotNull {
            val l = total!!.objectValue(it)
            val key = l.rechargeText("code")
            if (key.isBlank()) null else FeeLevel(key, l.intOrDefault("level"), l.rechargeText("name"))
        }.sortedBy { it.number }
        val options = map.arrayOrNull("data")
        val items = (0 until (options?.size ?: 0)).map {
            val o = options!!.objectValue(it); FeeOption(o.rechargeText("value"), o.rechargeText("name"))
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }
        if (levels.any { !it.key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,63}")) || it.number !in 1..8 } ||
            levels.map { it.key }.distinct().size != levels.size || levels.map { it.number }.distinct().size != levels.size)
            throw YktException("缴费查询层级配置异常")
        val display = map.objectOrNull("showData")?.let { d -> d.keys.iterator().asSequence().map { it to d.rechargeText(it) }.toList() }.orEmpty()
        val target = if (detail) map.objectOrNull("data")?.takeIf { it.size > 0 }?.withRoomInfo()?.toString() else null
        val tip = map.rechargeText("tipinfo").ifBlank { null }
        FeeQuery(levels, items, target, display, tip, tip == null && !map.booleanOrDefault("notPay", false))
    }

    /** The old frontend adds this client-side; the server derives the bill abstract from it. */
    private fun JsonObject.withRoomInfo(): JsonObject {
        if (stringOrDefault("myCustomInfo").isNotBlank()) return this
        val names = listOf("areaName", "buildingName", "floorName", "roomName").map { stringOrDefault(it) }
            .takeIf { parts -> parts.all { it.isNotBlank() } }
            ?: listOf("xiaoqu_id", "loudong_id", "louceng_id", "room_id").map { stringOrDefault(it).substringAfter('&', "") }
        return if (names.all { it.isNotBlank() }) withField("myCustomInfo", "房间：" + names.joinToString(" ")) else this
    }

    override suspend fun createFee(token: String, username: String, config: FeeConfiguration, query: FeeQuery, amountCents: Long): FeeOrder {
        config.amountRules.amountError(amountCents)?.let { throw YktException(it) }
        if (!query.canPay) throw YktException("当前目标不可缴费")
        val target = query.thirdParty ?: throw YktException("请先查询缴费目标")
        val data = post("/blade-pay/pay", token, mapOf(
            "feeitemid" to config.project.id, "tranamt" to RechargeMoney.yuan(amountCents), "flag" to "choose",
            "source" to "app", "paystep" to "0", "abstracts" to "", "redirect_url" to RechargeProtocol.SCHOOL + "/plat",
            "third_party" to target,
        ), sign = true).objectValue("data")
        val id = data.rechargeText("orderid")
        if (!RechargeProtocol.validId(id)) throw YktException("订单创建响应异常")
        val pending = PendingRecharge(username, target, config.project.id, amountCents, id)
        val info = orderInfo(token, pending)
        val methods = info.arrayOrNull("payList") ?: throw YktException("没有可用的支付方式")
        val supported = (0 until methods.size).map { methods.objectValue(it) }.filter {
            it.rechargeText("code") in setOf("ACCOUNTTSM", "TSM") && !it.booleanOrDefault("chooseAccount", false)
        }.map {
            FeeMethod(it.rechargeText("payid"), it.rechargeText("code"), if (it.rechargeText("code") == "ACCOUNTTSM") "一卡通" else "微信",
                it.booleanOrDefault("chooseAccount", false), it.intOrDefault("nopassword", 0))
        }.filter { it.id.matches(Regex("[0-9]{1,12}")) }
        if (supported.isEmpty()) throw YktException("没有受支持的支付方式，请在官网处理订单")
        return FeeOrder(pending, supported)
    }

    private suspend fun orderInfo(token: String, order: PendingRecharge): JsonObject {
        val payload = get("/charge/pay/getpayinfo", token, mapOf("orderid" to (order.orderId ?: throw YktException("订单号缺失"))))
        val o = payload.objectValue("order")
        verifyOrder(o, order, strict = true, verifyPayId = false)
        return payload
    }
    private fun payFields(order: PendingRecharge, method: FeeMethod) = mapOf(
        "orderid" to (order.orderId ?: throw YktException("订单号缺失")), "paytype" to method.code, "paytypeid" to method.id, "paystep" to "2",
    )
    private suspend fun verifyMethod(token: String, order: PendingRecharge, method: FeeMethod) {
        val info = orderInfo(token, order)
        requireUnpaid(info.objectValue("order"))
        val methods = info.arrayOrNull("payList") ?: throw YktException("支付方式不可用")
        val selected = (0 until methods.size).map { methods.objectValue(it) }.firstOrNull {
            it.rechargeText("payid") == method.id && it.rechargeText("code") == method.code && !it.booleanOrDefault("chooseAccount", false)
        } ?: throw YktException("支付方式已变化，请在官网处理")
        if (!method.wechat && (selected.intOrDefault("nopassword", 0) != method.noPassword ||
            selected.arrayOrNull("tradVerificationCombox")?.let { it.size > 0 && it.objectValue(0).intOrDefault("key", -1) != 0 } == true))
            throw YktException("支付验证方式已变化，请使用官网缴费")
        requirePayable(token, info.objectValue("order"))
    }

    private fun requireUnpaid(order: JsonObject) {
        when (order.intOrDefault("status", -1)) {
            1 -> throw FeeOrderAlreadyPaid()
            2 -> throw FeeOrderExpired()
            0 -> Unit
            else -> throw YktException("订单状态已变化，请在官网核实")
        }
    }
    private suspend fun requirePayable(token: String, order: JsonObject) {
        val current = get("/charge/order/getCurrentTime", token, emptyMap()).rechargeText("currentTime")
        FeeOrderValidity.requireCurrent(order.rechargeText("payexpdate"), current)
    }

    override suspend fun prepareFeeAccount(token: String, order: PendingRecharge, method: FeeMethod): FeeAccountPreparation {
        if (method.code != "ACCOUNTTSM" || method.chooseAccount) throw YktException("暂不支持该账户支付方式")
        verifyMethod(token, order, method)
        val data = post("/blade-pay/pay", token, payFields(order, method), sign = true).objectValue("data")
        val array = data.arrayOrNull("ccctype") ?: throw YktException("没有可支付电子账户")
        val returnedOrder = data.rechargeText("orderid")
        if (returnedOrder.isNotBlank() && returnedOrder != order.orderId) throw YktException("支付键盘订单不匹配")
        val accounts = (0 until array.size).map {
            val a = array.objectValue(it)
            FeeAccount(a.rechargeText("ccctype"), a.rechargeText("name").ifBlank { "统一账户" }, RechargeMoney.responseCents(a.scalarOrElement("balance")))
        }.filter { it.type.matches(Regex("[A-Za-z0-9_-]{1,64}")) }.distinctBy { it.type }
        val keyboard = data.objectOrNull("passwordMap") ?: throw YktException("支付键盘不可用，请使用官网缴费")
        if (accounts.isEmpty() || keyboard.size != 1) throw YktException("支付账户或键盘异常")
        val uuid = keyboard.keys.iterator().next()
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
        if (payload.scalarOrElement("data") != order.orderId) throw YktException("支付响应异常")
    }

    override suspend fun launchFeeWechat(token: String, order: PendingRecharge, method: FeeMethod): WechatRechargeLaunch {
        if (!method.wechat) throw YktException("支付方式不匹配")
        verifyMethod(token, order, method)
        val data = post("/blade-pay/pay", token, payFields(order, method) + ("isWX" to "0"), sign = true).objectValue("data")
        if (data.rechargeText("orderid") != order.orderId) throw YktException("支付订单不匹配")
        val linked = order.withOrder(order.orderId, method.id)
        return http.wechatLaunch(data.rechargeText("paysubmit"), linked, feePayment = true)
    }

    override suspend fun feeResult(token: String, order: PendingRecharge): RechargeResult {
        val payload = get("/charge/order/personal_data", token, buildMap {
            put("orderid", order.orderId ?: throw YktException("订单号缺失")); order.payId?.let { put("payid", it) }
        })
        val list = payload.arrayOrNull("orderList") ?: throw YktException("订单查询响应异常")
        if (list.size == 0) return RechargeResult(false, 0)
        if (list.size != 1) throw YktException("订单结果异常")
        val o = list.objectValue(0)
        val status = o.rechargeText("status").toIntOrNull() ?: -1
        verifyOrder(o, order, strict = status == 1, verifyPayId = true)
        if (status != 1) return RechargeResult(false, status, (o.scalarOrElement("msg") as? String)?.takeIf { it.isNotEmpty() })
        if (RechargeMoney.responseCents(o.scalarOrElement("actulamt")) != order.amountCents) throw YktException("实付金额不匹配")
        return RechargeResult(true, status)
    }
    private fun verifyOrder(o: JsonObject, order: PendingRecharge, strict: Boolean, verifyPayId: Boolean) {
        if (o.rechargeText("orderid") != order.orderId) throw YktException("缴费订单不匹配")
        val target = o.rechargeText("third_party")
        val targetMatches = if (!strict && target.isBlank()) true else runCatching { parseObject(target).jsonTargetEquals(parseObject(order.targetId)) }.getOrDefault(false)
        val amount = o.rechargeText("tranamt")
        val amountMatches = (!strict && amount.isBlank()) || RechargeMoney.responseCents(o.scalarOrElement("tranamt")) == order.amountCents
        val fees = o.arrayOrNull("feeitemlist")
        val feeMatches = (!strict && (o.isNull("feeitemlist") || fees?.size == 0)) ||
            (fees != null && (0 until fees.size).any { fees.objectValue(it).rechargeText("feeitemid") == order.feeItemId })
        val payId = o.rechargeText("payid")
        val payMatches = !verifyPayId || order.payId == null || (!strict && payId.isBlank()) || payId == order.payId
        if (!targetMatches || !amountMatches || !feeMatches || !payMatches) throw YktException("缴费目标或金额不匹配")
    }
}
