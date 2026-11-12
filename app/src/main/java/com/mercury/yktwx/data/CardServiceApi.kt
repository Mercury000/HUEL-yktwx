package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import java.util.Base64
import okhttp3.Call

class CardServiceApi(private val client: Call.Factory = AppHttp.service) : CardService {
    private val http = PaymentHttp(client)
    private suspend fun get(path: String, token: String, params: Map<String, String> = emptyMap(), preserveAuthMessage: Boolean = false) =
        http.json(http.request(path, token, params), preserveAuthenticationMessage = preserveAuthMessage)
    private fun config(data: JsonObject, key: String): JsonObject = when (val raw = data.scalarOrElement(key)) {
        is JsonObject -> raw
        is String -> runCatching { parseObject(raw) }.getOrElse { throw YktException("校园卡服务配置异常") }
        null -> jsonObjectOf()
        else -> if (raw === JsonNull) jsonObjectOf() else throw YktException("校园卡服务配置异常")
    }
    private suspend fun settings(token: String, preserveAuthMessage: Boolean = false): Triple<JsonObject, JsonObject, JsonObject> {
        val data = get("/berserker-app/frontInfo", token, preserveAuthMessage = preserveAuthMessage).objectValue("data")
        val front = config(data, "getFrontConfig")
        val ecard = config(data, "getEcardConfig")
        val business = config(data, "getBusinessConfig")
        return Triple(front, ecard, config(business, "businessHandleConfig"))
    }
    private suspend fun cards(token: String, preserveAuthMessage: Boolean = false, verifyPaymentState: Boolean = false): List<CampusCard> {
        val data = get("/berserker-app/ykt/tsm/getCampusCards", token, preserveAuthMessage = preserveAuthMessage).objectValue("data")
        if (data.rechargeText("retcode") != "0") throw YktException(
            (data.scalarOrElement("errmsg") as? String)?.takeIf { it.isNotEmpty() } ?: "读取校园卡状态失败",
        )
        val list = data.arrayOrNull("card") ?: throw YktException("没有可用的校园卡")
        return (0 until list.size).map { index ->
            val card = list.objectValue(index)
            val account = card.rechargeText("account")
            if (account.isBlank() || card.rechargeText("lostflag") !in listOf("0", "1")) throw YktException("校园卡状态数据异常")
            if (verifyPaymentState && (card.rechargeText("freezeflag") !in listOf("0", "1") || card.intOrDefault("acc_status", -1) < 0))
                throw YktException("校园卡状态数据异常")
            CampusCard(account, card.rechargeText("cardname"), card.rechargeText("sno"), card.rechargeText("lostflag") == "1",
                card.intOrDefault("freezeflag", -1) != 0, card.intOrDefault("acc_status", -1))
        }
    }
    /** Read-only payment preflight, scoped to the selected unified account. */
    internal suspend fun paymentCard(token: String, account: String): CampusCard =
        cards(token, verifyPaymentState = true).singleOrNull { it.account == account }
            ?: throw YktException("无法确认当前付款账户的校园卡状态")

    override suspend fun cardConfiguration(token: String): CardServiceConfiguration {
        val (front, _, business) = settings(token)
        val lostNeedsPassword = front.rechargeText("lockFlag") == "1" || business.rechargeText("lockFlag") == "1"
        val unlostNeedsPassword = front.rechargeText("unlockFlag") != "0" && business.rechargeText("unlockFlag") != "0"
        return CardServiceConfiguration(cards(token), lostNeedsPassword, unlostNeedsPassword)
    }
    override suspend fun passwordConfiguration(token: String): CardPasswordConfiguration {
        val (front, ecard, business) = settings(token, preserveAuthMessage = true)
        val changeFlag = front.rechargeText("changePasswordFlag")
        // Only the observed six-digit, old-password verification flow is supported.
        if (ecard.rechargeText("passwordType") == "1" || changeFlag.getOrNull(1) in listOf('2', '4') ||
            business.rechargeText("modifyPwdFlag") == "0") throw YktException("当前改密方式请在官网完成")
        val eligible = cards(token, preserveAuthMessage = true).filter { !it.frozen && it.status == 0 && (!it.lost || ecard.rechargeText("showLost") == "1") }
        if (eligible.isEmpty()) throw YktException("没有可修改密码的校园卡，请在官网检查卡状态")
        return CardPasswordConfiguration(eligible)
    }
    override suspend fun modifyCardPassword(token: String, card: CampusCard, oldPassword: String, newPassword: String, confirmation: String) {
        if (listOf(oldPassword, newPassword, confirmation).any { !it.matches(Regex("[0-9]{6}")) })
            throw YktException("请输入 6 位数字查询密码")
        if (newPassword != confirmation) throw YktException("两次输入的新密码不一致")
        val current = passwordConfiguration(token).cards.singleOrNull { it.account == card.account }
            ?: throw YktException("校园卡已变更或不可用，请重新选择")
        suspend fun encode(password: String): String {
            val keyboard = get("/berserker-secure/keyboard", token, mapOf("type" to "Number", "order" to "0"),
                preserveAuthMessage = true).objectValue("data")
            return NumberKeyboardEncoder.encode(password, keyboard.rechargeText("numberKeyboard"), keyboard.rechargeText("uuid"))
        }
        val body = jsonObjectOf("account" to (current.account), "oldpw" to (encode(oldPassword)), "newpw" to (encode(newPassword)), "renewpw" to (encode(confirmation)), "synAccessSource" to ("h5"))
        // One write only. An ambiguous result must not be replayed or called successful.
        val response = try { http.request("/berserker-app/ykt/tsm/modifyPwd", token, jsonBody = body) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: NetworkFailure) { throw YktException("修改结果未确认，请先在官网核实，勿重复提交") }
        val outer = runCatching { parseObject(response.body) }.getOrNull()
        val data = outer?.objectOrNull("data")
        // Preserve official text exactly (including whitespace); never classify failure by message wording.
        val msg = (outer?.scalarOrElement("msg") as? String)?.takeIf { it.isNotEmpty() }
        val errmsg = (data?.scalarOrElement("errmsg") as? String)?.takeIf { it.isNotEmpty() }
            ?: (data?.scalarOrElement("msg") as? String)?.takeIf { it.isNotEmpty() }
        val unknown = "修改结果未确认，请先在官网核实，勿重复提交"
        if (response.status in listOf(401, 403) || outer?.intOrDefault("code") in listOf(401, 403, 4011, 4030, 4037, 4038))
            throw AuthenticationRequired(msg ?: errmsg ?: "登录已失效，请重新登录")
        if (response.status !in 200..299 || outer?.intOrDefault("code") != 200 || outer.scalarOrElement("success") == false) {
            if (outer?.intOrDefault("code") == 60005) throw InputRejected(msg ?: errmsg ?: unknown, setOf(FormField.OldPassword))
            throw YktException(msg ?: errmsg ?: unknown)
        }
        if (data == null) throw YktException(msg ?: unknown)
        val retcode = data.rechargeText("retcode")
        if (retcode.isBlank()) throw YktException(errmsg ?: msg ?: unknown)
        if (retcode != "0") {
            val reason = errmsg ?: msg ?: "修改查询密码失败"
            if (retcode == "60005") throw InputRejected(reason, setOf(FormField.OldPassword))
            throw YktException(reason)
        }
        val echoed = data.rechargeText("account")
        if (echoed.isNotBlank() && echoed != current.account)
            throw YktException("修改结果未确认，请先在官网核实，勿重复提交")
    }
    override suspend fun changeCardState(token: String, card: CampusCard, action: CampusCardAction, password: String): List<CampusCard> {
        require(action == CampusCardAction.Lost || action == CampusCardAction.Unlost)
        val desiredLost = action == CampusCardAction.Lost
        val current: CampusCard
        var body: JsonObject
        // Everything here is read-only. An error must not imply that a write was submitted.
        try {
            val configuration = cardConfiguration(token)
            current = configuration.cards.singleOrNull { it.account == card.account } ?: throw YktException("校园卡已变更，请重新选择")
            if (current.lost == desiredLost) return configuration.cards
            if (current.frozen || current.status != 0) throw YktException("校园卡已冻结或状态异常，请在官网处理")
            body = jsonObjectOf("account" to (current.account), "synAccessSource" to ("h5"))
            val needsPassword = if (desiredLost) configuration.lostNeedsPassword else configuration.unlostNeedsPassword
            if (needsPassword) {
                if (!password.matches(Regex("[0-9]{6}"))) throw YktException("请输入 6 位查询密码")
                val keyboard = get("/berserker-secure/keyboard", token, mapOf("type" to "Number", "order" to "0")).objectValue("data")
                body = body.withField("pwd", NumberKeyboardEncoder.encode(password, keyboard.rechargeText("numberKeyboard"), keyboard.rechargeText("uuid"))).withField("pwdType", 1)
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
        val body = runCatching { parseObject(response.body) }.getOrNull()
            ?: throw CardOperationUnconfirmed("服务器响应异常")
        val data = body.objectOrNull("data")
        val message = listOf(data?.scalarOrElement("errmsg"), body.scalarOrElement("msg"), body.scalarOrElement("message"), body.scalarOrElement("error_description"))
            .filterIsInstance<String>().firstOrNull { it.isNotEmpty() }
        val code = body.intOrDefault("code", -1)
        if (code in listOf(401, 403, 4011, 4030, 4037, 4038)) throw AuthenticationRequired()
        val retcode = data?.rechargeText("retcode").orEmpty()
        val rejectionFields = if (code == 60005 || retcode == "60005") setOf(FormField.Password) else emptySet()
        val acceptedRetcodes = listOf("0", if (desiredLost) "60007" else "60008")
        val contradictoryAcceptance = retcode in acceptedRetcodes &&
            (code != 200 || body.scalarOrElement("success") == false || response.status !in 200..299)
        if ((response.status !in 200..299 && response.status !in 400..499) || contradictoryAcceptance)
            throw CardOperationUnconfirmed(message ?: "操作响应未确认")
        if (code != 200) {
            if ((body.scalarOrElement("success") == false || code >= 0 && code !in 500..599 || response.status in 400..499) &&
                body.scalarOrElement("success") != true && code !in 500..599)
                throw CardOperationRejected(message ?: "Error", rejectionFields)
            throw CardOperationUnconfirmed(message ?: "操作响应未确认")
        }
        // Outer success describes the envelope; a non-success inner retcode is a business rejection.
        if ((retcode.isNotEmpty() && retcode !in acceptedRetcodes) ||
            (body.scalarOrElement("success") == false && retcode.isEmpty()))
            throw CardOperationRejected(message ?: "Error", rejectionFields)
        if (retcode !in acceptedRetcodes || body.scalarOrElement("success") == false)
            throw CardOperationUnconfirmed(message ?: "操作响应未确认")
        // The official endpoint need not echo the account. Reject a conflicting echo, not its absence.
        val returnedAccount = data?.rechargeText("account").orEmpty()
        if (returnedAccount.isNotEmpty() && returnedAccount != account)
            throw CardOperationUnconfirmed("操作结果与所选校园卡不匹配")
    }
    override suspend fun recipientCaptcha(token: String): RecipientCaptcha {
        val response = http.request("/berserker-auth/oauth/captcha", token)
        // This endpoint is not wrapped in code/data, unlike the other first-party APIs.
        val body = runCatching { parseObject(response.body) }.getOrElse { throw YktException("验证码读取失败，请重试") }
        if (response.status in listOf(401, 403) || body.intOrDefault("code") in listOf(401, 403, 4011, 4030, 4037, 4038)) throw AuthenticationRequired()
        if (response.status !in 200..299 || (body.containsKey("code") && body.intOrDefault("code") != 200)) throw YktException("验证码读取失败，请重试")
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
        val raw = runCatching { parseObject(response.body) }.getOrNull()
        if (response.status == 200 && raw?.intOrDefault("code") in listOf(8002, 8003)) throw RecipientLookupRejected("验证码有误，请重新输入", setOf(FormField.Captcha))
        val data = http.json(response).objectOrNull("data") ?: throw RecipientLookupRejected("未查询到收款账户")
        if (data.rechargeText("retcode") != "0") throw RecipientLookupRejected(if (data.rechargeText("retcode") == "60003") "账户信息不存在，请检查学工号或证件号" else "查询失败，请核实收款人信息")
        val (_, ecard, _) = settings(token)
        val list = data.arrayOrNull("card") ?: throw RecipientLookupRejected("没有可充值账户")
        val result = mutableListOf<RechargeRecipient>()
        var unavailable: RechargeTargetUnavailable? = null
        for (i in 0 until list.size) {
            val card = list.objectValue(i)
            // Match the official recharge configuration for the selected recipient.
            try { RechargeErrors.requireAvailable(card, ecard) }
            catch (e: RechargeTargetUnavailable) { unavailable = unavailable ?: e; continue }
            val account = card.rechargeText("account")
            if (account.isBlank()) continue
            fun add(target: String, name: String) {
                if (target.isNotBlank()) result += RechargeRecipient(account, target, card.rechargeText("name"), card.rechargeText("sno"), name)
            }
            if (ecard.rechargeText("type") != "1") add(account, card.rechargeText("cardname").ifBlank { "校园卡" })
            if (ecard.rechargeText("type") != "2") {
                val accounts = card.arrayOrNull("accinfo")
                (0 until (accounts?.size ?: 0)).map { accounts!!.objectValue(it) }.sortedBy { it.rechargeText("type") }.forEach {
                    add(it.rechargeText("type"), it.rechargeText("name").ifBlank { "统一账户" })
                }
            }
        }
        if (result.isEmpty()) throw RecipientLookupRejected(unavailable?.message ?: "没有正常的可充值账户，请在官网检查卡状态")
        return result
    }
    override suspend fun recipientRechargeConfiguration(token: String, recipient: RechargeRecipient): RechargeConfiguration {
        val (front, _, _) = settings(token)
        val id = front.rechargeText("recharge")
        if (!id.matches(Regex("[0-9]{1,12}"))) throw YktException("充值项目配置异常")
        val fee = get("/charge/feeitem/singleFeeitem", token, mapOf("feeitemid" to id)).objectValue("feeitem")
        return RechargeApi.amountConfiguration(id, recipient.targetId, front, fee, recipient)
    }
}
