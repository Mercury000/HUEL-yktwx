package com.mercury.yktwx.data

import org.json.JSONObject
import java.net.URL
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

class CardServiceApi(private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }) : CardService {
    private val http = PaymentHttp(openConnection)
    private suspend fun get(path: String, token: String, params: Map<String, String> = emptyMap()) = http.json(http.request(path, token, params))
    private fun config(data: JSONObject, key: String): JSONObject = when (val raw = data.opt(key)) {
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrElse { throw YktException("校园卡服务配置异常") }
        null -> JSONObject()
        else -> if (raw === JSONObject.NULL) JSONObject() else throw YktException("校园卡服务配置异常")
    }
    private suspend fun settings(token: String): Triple<JSONObject, JSONObject, JSONObject> {
        val data = get("/berserker-app/frontInfo", token).getJSONObject("data")
        val front = config(data, "getFrontConfig")
        val ecard = config(data, "getEcardConfig")
        val business = config(data, "getBusinessConfig")
        return Triple(front, ecard, config(business, "businessHandleConfig"))
    }
    private suspend fun cards(token: String): List<CampusCard> {
        val data = get("/berserker-app/ykt/tsm/getCampusCards", token).getJSONObject("data")
        if (data.rechargeText("retcode") != "0") throw YktException(
            (data.opt("errmsg") as? String)?.takeIf { it.isNotEmpty() } ?: "读取校园卡状态失败",
        )
        val list = data.optJSONArray("card") ?: throw YktException("没有可用的校园卡")
        return (0 until list.length()).map { index ->
            val card = list.getJSONObject(index)
            val account = card.rechargeText("account")
            if (account.isBlank() || card.rechargeText("lostflag") !in listOf("0", "1")) throw YktException("校园卡状态数据异常")
            CampusCard(account, card.rechargeText("cardname"), card.rechargeText("sno"), card.rechargeText("lostflag") == "1",
                card.optInt("freezeflag", -1) != 0, card.optInt("acc_status", -1))
        }
    }
    override suspend fun cardConfiguration(token: String): CardServiceConfiguration {
        val (front, _, business) = settings(token)
        val lostNeedsPassword = front.rechargeText("lockFlag") == "1" || business.rechargeText("lockFlag") == "1"
        val unlostNeedsPassword = front.rechargeText("unlockFlag") != "0" && business.rechargeText("unlockFlag") != "0"
        return CardServiceConfiguration(cards(token), lostNeedsPassword, unlostNeedsPassword)
    }
    override suspend fun changeCardState(token: String, card: CampusCard, action: CampusCardAction, password: String): List<CampusCard> {
        require(action == CampusCardAction.Lost || action == CampusCardAction.Unlost)
        val desiredLost = action == CampusCardAction.Lost
        val current: CampusCard
        val body: JSONObject
        // Everything here is read-only. An error must not imply that a write was submitted.
        try {
            val configuration = cardConfiguration(token)
            current = configuration.cards.singleOrNull { it.account == card.account } ?: throw YktException("校园卡已变更，请重新选择")
            if (current.lost == desiredLost) return configuration.cards
            if (current.frozen || current.status != 0) throw YktException("校园卡已冻结或状态异常，请在官网处理")
            body = JSONObject().put("account", current.account).put("synAccessSource", "h5")
            val needsPassword = if (desiredLost) configuration.lostNeedsPassword else configuration.unlostNeedsPassword
            if (needsPassword) {
                if (!password.matches(Regex("[0-9]{6}"))) throw YktException("请输入 6 位查询密码")
                val keyboard = get("/berserker-secure/keyboard", token, mapOf("type" to "Number", "order" to "0")).getJSONObject("data")
                body.put("pwd", NumberKeyboardEncoder.encode(password, keyboard.rechargeText("numberKeyboard"), keyboard.rechargeText("uuid"))).put("pwdType", 1)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: AuthenticationRequired) { throw e }
        catch (e: InvalidCredentials) { throw e }
        catch (e: CaptchaRequired) { throw e }
        catch (e: YktException) { throw CardOperationNotStarted(e.message ?: "操作检查未完成，请稍后重试") }
        catch (_: java.io.IOException) { throw CardOperationNotStarted(NetworkFailure().message ?: "网络连接失败") }
        catch (_: Exception) { throw CardOperationNotStarted("操作检查未完成，请稍后重试") }

        // Do not retry this POST. Only this boundary can produce an ambiguous write outcome.
        val response = try {
            http.request("/berserker-app/ykt/tsm/" + if (desiredLost) "lostCard" else "unlostCard", token, jsonBody = body)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: YktException) { throw CardOperationUnconfirmed(e.message ?: "操作响应未确认") }
        catch (_: Exception) { throw CardOperationUnconfirmed("操作响应未确认") }
        requireCardWriteAccepted(response, current.account, desiredLost)

        // Acceptance is not final state. An unsuccessful read is not a password/business rejection.
        val refreshed = try { cards(token) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: YktException) { throw CardOperationUnconfirmed(e.message ?: "读取操作后卡状态失败") }
        catch (_: Exception) { throw CardOperationUnconfirmed("读取操作后卡状态失败") }
        if (refreshed.singleOrNull { it.account == current.account }?.lost != desiredLost)
            throw CardOperationUnconfirmed("操作已提交，卡状态尚未确认，请刷新")
        return refreshed
    }

    private fun requireCardWriteAccepted(response: PaymentHttp.Response, account: String, desiredLost: Boolean) {
        if (response.status in listOf(401, 403)) throw AuthenticationRequired()
        val body = runCatching { JSONObject(response.body) }.getOrNull()
            ?: throw CardOperationUnconfirmed("服务器响应异常")
        val data = body.optJSONObject("data")
        val message = listOf(data?.opt("errmsg"), body.opt("msg"), body.opt("message"), body.opt("error_description"))
            .filterIsInstance<String>().firstOrNull { it.isNotEmpty() }
        val code = body.optInt("code", -1)
        if (code in listOf(401, 403, 4011, 4030, 4037, 4038)) throw AuthenticationRequired()
        val retcode = data?.rechargeText("retcode").orEmpty()
        val acceptedRetcodes = listOf("0", if (desiredLost) "60007" else "60008")
        val contradictoryAcceptance = retcode in acceptedRetcodes &&
            (code != 200 || body.opt("success") == false || response.status !in 200..299)
        if ((response.status !in 200..299 && response.status !in 400..499) || contradictoryAcceptance)
            throw CardOperationUnconfirmed(message ?: "操作响应未确认")
        if (code != 200) {
            if ((body.opt("success") == false || code >= 0 && code !in 500..599 || response.status in 400..499) &&
                body.opt("success") != true && code !in 500..599)
                throw CardOperationRejected(message ?: "Error")
            throw CardOperationUnconfirmed(message ?: "操作响应未确认")
        }
        // Outer success describes the envelope; a non-success inner retcode is a business rejection.
        if ((retcode.isNotEmpty() && retcode !in acceptedRetcodes) ||
            (body.opt("success") == false && retcode.isEmpty()))
            throw CardOperationRejected(message ?: "Error")
        if (retcode !in acceptedRetcodes || body.opt("success") == false)
            throw CardOperationUnconfirmed(message ?: "操作响应未确认")
        // The official endpoint need not echo the account. Reject a conflicting echo, not its absence.
        val returnedAccount = data?.rechargeText("account").orEmpty()
        if (returnedAccount.isNotEmpty() && returnedAccount != account)
            throw CardOperationUnconfirmed("操作结果与所选校园卡不匹配")
    }
    override suspend fun recipientCaptcha(token: String): RecipientCaptcha {
        val response = http.request("/berserker-auth/oauth/captcha", token)
        // This endpoint is not wrapped in code/data, unlike the other first-party APIs.
        val body = runCatching { JSONObject(response.body) }.getOrElse { throw YktException("验证码读取失败，请重试") }
        if (response.status in listOf(401, 403) || body.optInt("code") in listOf(401, 403, 4011, 4030, 4037, 4038)) throw AuthenticationRequired()
        if (response.status !in 200..299 || (body.has("code") && body.optInt("code") != 200)) throw YktException("验证码读取失败，请重试")
        val key = body.rechargeText("key")
        val image = body.rechargeText("image")
        val encoded = if (image.startsWith("data:image/")) image.substringAfter(";base64,", "") else image
        if (key.isBlank() || encoded.isBlank() || encoded.length > 700_000) throw YktException("验证码格式不支持，请在官网操作")
        val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse { throw YktException("验证码图片无效，请重试") }
        if (bytes.isEmpty()) throw YktException("验证码图片无效，请重试")
        return RecipientCaptcha(key, bytes)
    }
    override suspend fun rechargeRecipients(token: String, type: RecipientQueryType, value: String, captcha: RecipientCaptcha, code: String): List<RechargeRecipient> {
        val id = value.trim()
        if (id.isBlank() || id.length > 32 || !id.matches(Regex("[0-9Xx]+"))) throw YktException("请输入正确的" + type.label)
        if (code.isBlank() || code.length > 16) throw YktException("请输入验证码")
        val response = http.request("/berserker-app/ykt/tsm/idtypeQueryCard", token, mapOf("idtype" to type.key, "id" to id,
            "scene" to "recharge", "captcha_header_key" to captcha.key, "captcha_header_code" to code.trim()))
        val raw = runCatching { JSONObject(response.body) }.getOrNull()
        if (response.status == 200 && raw?.optInt("code") in listOf(8002, 8003)) throw RecipientLookupRejected(true, "验证码有误，请重新输入")
        val data = http.json(response).optJSONObject("data") ?: throw RecipientLookupRejected(false, "未查询到收款账户")
        if (data.rechargeText("retcode") != "0") throw RecipientLookupRejected(false,
            if (data.rechargeText("retcode") == "60003") "账户信息不存在，请检查学工号或证件号" else "查询失败，请核实收款人信息")
        val (_, ecard, _) = settings(token)
        val list = data.optJSONArray("card") ?: throw RecipientLookupRejected(false, "没有可充值账户")
        val result = mutableListOf<RechargeRecipient>()
        var unavailable: RechargeTargetUnavailable? = null
        for (i in 0 until list.length()) {
            val card = list.getJSONObject(i)
            // Match the official recharge configuration for the selected recipient.
            try { RechargeErrors.requireAvailable(card, ecard) }
            catch (e: RechargeTargetUnavailable) { unavailable = unavailable ?: e; continue }
            val account = card.rechargeText("account")
            if (account.isBlank()) continue
            fun add(target: String, name: String, balance: Long) {
                if (target.isNotBlank()) result += RechargeRecipient(account, target, card.rechargeText("name"), card.rechargeText("sno"), name, balance)
            }
            if (ecard.rechargeText("type") != "1") add(account, card.rechargeText("cardname").ifBlank { "校园卡" }, card.optLong("db_balance") + card.optLong("unsettle_amount"))
            if (ecard.rechargeText("type") != "2") {
                val accounts = card.optJSONArray("accinfo")
                (0 until (accounts?.length() ?: 0)).map { accounts!!.getJSONObject(it) }.sortedBy { it.rechargeText("type") }.forEach {
                    add(it.rechargeText("type"), it.rechargeText("name").ifBlank { "统一账户" }, it.optLong("balance"))
                }
            }
        }
        if (result.isEmpty()) throw RecipientLookupRejected(false, unavailable?.message ?: "没有正常的可充值账户，请在官网检查卡状态")
        return result
    }
    override suspend fun recipientRechargeConfiguration(token: String, recipient: RechargeRecipient): RechargeConfiguration {
        val (front, _, _) = settings(token)
        val id = front.rechargeText("recharge")
        if (!id.matches(Regex("[0-9]{1,12}"))) throw YktException("充值项目配置异常")
        val fee = get("/charge/feeitem/singleFeeitem", token, mapOf("feeitemid" to id)).getJSONObject("feeitem")
        return RechargeApi.amountConfiguration(id, recipient.targetId, recipient.accountName, recipient.balanceCents, front, fee, recipient)
    }
}
