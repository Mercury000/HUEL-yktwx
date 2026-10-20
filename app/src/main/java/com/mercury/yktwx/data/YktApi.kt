package com.mercury.yktwx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

interface YktService {
    suspend fun login(credentials: Credentials): Session
    suspend fun payment(token: String): PaymentBatch
    suspend fun bills(
        token: String,
        current: Int,
        size: Int,
        timeFrom: String,
        timeTo: String,
        typeId: String?,
        amountFromCents: Long?,
        amountToCents: Long?,
        info: String?,
    ): BillPageData
    suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary
    suspend fun billTypes(token: String): List<BillType>
    suspend fun billCategories(token: String, type: Int, timeFrom: String, timeTo: String): List<BillCategory>
    suspend fun billDaily(token: String, type: Int, month: String): List<BillDailyPoint>
    suspend fun billRanking(token: String, type: Int, timeFrom: String, timeTo: String, limit: Int): List<BillRecord>
}

/** First-party HTTPS only; no cookie jar, redirects, insecure TLS or automatic login retries. */
class YktApi(
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : YktService {
    private suspend fun request(path: String, params: Map<String, String> = emptyMap(), token: String? = null, login: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val query = (params + ("synAccessSource" to "h5")).entries.joinToString("&") { "${escape(it.key)}=${escape(it.value)}" }
        val connection = openConnection(URI(BASE + path + if (login) "" else "?$query").toURL())
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false; connection.useCaches = false
            connection.setRequestProperty("synAccessSource", "h5")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            if (token != null) connection.setRequestProperty("synjones-auth", "bearer $token")
            if (login) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("mobile_service_platform:mobile_service_platform_secret".toByteArray()))
                connection.outputStream.use { it.write(query.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readNBytes(1_048_577) } ?: byteArrayOf()
            if (bytes.size > 1_048_576) throw YktException("服务器响应异常")
            val payload = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            val code = payload?.optInt("code", -1) ?: -1
            if (status >= 500) throw YktException("服务器暂不可用，请稍后重试")
            if (status in 300..399) throw YktException("服务发生跳转，请在官网检查登录状态")
            if (status == 429) throw YktException("请求过于频繁，请稍后重试")
            if (login) {
                if (code in listOf(8002, 8003)) throw CaptchaRequired()
                if (status !in 200..299 || payload?.has("access_token") != true) throw InvalidCredentials()
            } else {
                if (status in listOf(401, 403) || code in listOf(401, 403, 4011, 4030, 4037, 4038)) throw AuthenticationRequired()
                if (status !in 200..299 || code != 200) throw YktException("服务暂不可用，请在官网检查账户状态")
            }
            payload ?: throw YktException("服务器响应异常")
        } catch (e: YktException) { throw e }
        catch (_: java.io.IOException) { throw NetworkFailure() }
        finally { connection.disconnect() }
    }
    override suspend fun login(credentials: Credentials): Session {
        if (credentials.username.isEmpty() || credentials.username.any { it !in '0'..'9' }) throw YktException("请输入正确的学号或工号")
        KeyboardEncoder.validate(credentials.password)
        val config = request("/berserker-app/frontInfo").getJSONObject("data")
        val loginType = LoginConfiguration.selectLoginType(config)
        val keyboard = request("/berserker-secure/keyboard", mapOf("type" to "Standard", "order" to "0")).getJSONObject("data")
        val encoded = KeyboardEncoder.encode(credentials.password, KeyboardEncoder.layouts.keys.associateWith { keyboard.optString(it) }, keyboard.optString("uuid"))
        val response = request("/berserker-auth/oauth/token", mapOf(
            "username" to credentials.username, "password" to encoded, "grant_type" to "password", "scope" to "all",
            "loginFrom" to "h5", "logintype" to loginType, "device_token" to "h5",
        ), login = true)
        if (response.optBoolean("is_password_expired") || response.optBoolean("is_first_login")) throw YktException("请先在官网完成首次登录或密码更新")
        if (response.optString("token_type", "bearer").lowercase() != "bearer") throw YktException("登录凭证类型异常")
        val token = response.optString("access_token")
        if (token.isBlank() || token.any { it <= ' ' || it >= '\u007f' }) throw YktException("登录凭证异常")
        val lifetime = response.optLong("expires_in").coerceIn(1, 31_536_000)
        return Session(token, System.currentTimeMillis() + lifetime * 1000)
    }
    override suspend fun payment(token: String): PaymentBatch {
        val list = request("/berserker-app/ykt/tsm/codebarPayinfo", token = token).getJSONArray("data")
        val selected = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull {
            it.optString("code") == "ACCOUNT" && it.optInt("status") == 1 && it.optInt("lostflag") == 0 && it.optInt("freezeflag") == 0
        } ?: throw YktException("没有可用的统一账户，请在官网检查挂失或冻结状态")
        val account = PaymentAccount(selected.getString("account"), selected.getString("payacc"), selected.getString("paytype"), selected.optString("name", "统一账户"), selected.optLong("accinfo_balance"))
        val started = System.currentTimeMillis()
        val data = request("/berserker-app/ykt/tsm/batchGetBarCodeGet", mapOf("account" to account.account, "payacc" to account.payacc, "paytype" to account.paytype), token).getJSONObject("data")
        val array = data.optJSONArray("barcode") ?: throw YktException("未取得付款码，请刷新或在官网检查账户")
        val codes = (0 until array.length()).map { array.getString(it) }
        if (codes.isEmpty() || codes.any { code -> code.length != 20 || code.any { it !in '0'..'9' } }) throw YktException("付款码数据异常")
        val expiry = data.optLong("expires")
        if (expiry <= 0) throw YktException("付款码有效期异常")
        return PaymentBatch(codes, expiry, started, account)
    }
    override suspend fun bills(
        token: String,
        current: Int,
        size: Int,
        timeFrom: String,
        timeTo: String,
        typeId: String?,
        amountFromCents: Long?,
        amountToCents: Long?,
        info: String?,
    ): BillPageData {
        val params = buildMap {
            put("current", current.toString()); put("size", size.toString())
            put("timeFrom", timeFrom); put("timeTo", timeTo)
            typeId?.takeIf { it.isNotBlank() }?.let { put("typeId", it) }
            amountFromCents?.let { put("amountFrom", it.toString()) }
            amountToCents?.let { put("amountTo", it.toString()) }
            info?.trim()?.takeIf { it.isNotEmpty() }?.let { put("info", it) }
        }
        return BillParser.page(request("/berserker-search/search/personal/turnover", params, token).getJSONObject("data"))
    }
    override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
        val data = request(
            "/berserker-search/statistics/turnover/count",
            mapOf("timeFrom" to timeFrom, "timeTo" to timeTo), token,
        ).getJSONObject("data")
        return BillParser.summary(data)
    }
    override suspend fun billTypes(token: String): List<BillType> =
        BillParser.types(request("/berserker-search/search/turnoverType", token = token).getJSONArray("data"))
    override suspend fun billCategories(token: String, type: Int, timeFrom: String, timeTo: String): List<BillCategory> {
        val data = request(
            "/berserker-search/statistics/turnover",
            mapOf("type" to type.toString(), "timeFrom" to timeFrom, "timeTo" to timeTo), token,
        ).getJSONArray("data")
        return BillParser.categories(data)
    }
    override suspend fun billDaily(token: String, type: Int, month: String): List<BillDailyPoint> {
        val data = request(
            "/berserker-search/statistics/turnover/sum/user",
            mapOf("dateStr" to month, "dateType" to "month", "statisticsDateStr" to "day", "type" to type.toString()), token,
        ).getJSONObject("data")
        return BillParser.daily(data)
    }
    override suspend fun billRanking(token: String, type: Int, timeFrom: String, timeTo: String, limit: Int): List<BillRecord> {
        val params = mapOf(
            "current" to "1", "size" to limit.toString(), "timeFrom" to timeFrom, "timeTo" to timeTo,
            "type" to type.toString(), "sortFields" to "tranamt", "sortType" to "desc",
        )
        return BillParser.page(request("/berserker-search/search/personal/turnover", params, token).getJSONObject("data")).items
    }
    private fun escape(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
    private companion object { const val BASE = "https://yktwx2.huel.edu.cn" }
}
