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
        if (data.rechargeText("retcode") != "0") throw YktException("读取校园卡状态失败")
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
        val configuration = cardConfiguration(token)
        val current = configuration.cards.singleOrNull { it.account == card.account } ?: throw YktException("校园卡已变更，请重新选择")
        val desiredLost = action == CampusCardAction.Lost
        if (current.lost == desiredLost) return configuration.cards // Already reached the requested state; no duplicate write.
        if (current.frozen || current.status != 0) throw YktException("校园卡已冻结或状态异常，请在官网处理")
        val body = JSONObject().put("account", current.account).put("synAccessSource", "h5")
        val needsPassword = if (desiredLost) configuration.lostNeedsPassword else configuration.unlostNeedsPassword
        if (needsPassword) {
            if (!password.matches(Regex("[0-9]{6}"))) throw YktException("请输入 6 位查询密码")
            val keyboard = get("/berserker-secure/keyboard", token, mapOf("type" to "Number", "order" to "0")).getJSONObject("data")
            body.put("pwd", NumberKeyboardEncoder.encode(password, keyboard.rechargeText("numberKeyboard"), keyboard.rechargeText("uuid"))).put("pwdType", 1)
        }
        val response = http.json(http.request("/berserker-app/ykt/tsm/" + if (desiredLost) "lostCard" else "unlostCard", token, jsonBody = body))
        val data = response.optJSONObject("data") ?: throw YktException("操作结果未确认，请刷新卡状态")
        if (data.rechargeText("retcode") !in listOf("0", if (desiredLost) "60007" else "60008")) {
            throw CardOperationRejected(when (data.rechargeText("retcode")) {
                "60005" -> "查询密码错误，请重新输入"
                else -> "操作失败，请检查卡片状态或查询密码"
            })
        }
        if (data.rechargeText("account") != current.account) throw YktException("操作结果与所选校园卡不匹配")
        val refreshed = cards(token)
        if (refreshed.singleOrNull { it.account == current.account }?.lost != desiredLost) throw YktException("操作已提交，卡状态尚未确认，请刷新")
        return refreshed
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
        for (i in 0 until list.length()) {
            val card = list.getJSONObject(i)
            // Same conservative policy as the existing native recharge flow.
            if (card.optInt("lostflag", -1) != 0 || card.optInt("freezeflag", -1) != 0 || (card.has("acc_status") && card.optInt("acc_status", -1) != 0)) continue
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
        if (result.isEmpty()) throw RecipientLookupRejected(false, "没有正常的可充值账户，请在官网检查卡状态")
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
