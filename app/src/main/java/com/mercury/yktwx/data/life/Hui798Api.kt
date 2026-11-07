package com.mercury.yktwx.data.life

import com.mercury.yktwx.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal object Hui798Protocol {
    fun loginSign(phone: String, code: String, salt: String): String = MessageDigest.getInstance("MD5")
        .digest("authCode=$code&un=$phone$salt".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Only the 798 wire protocol; presentation and device lifecycle live in our own app. */
class Hui798Api(
    gateway: String = BuildConfig.HUI798_API_GATEWAY,
    private val salt: String = BuildConfig.HUI798_SIGN_SALT,
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : Hui798Service {
    private val base = gateway.trim().trimEnd('/')
    private suspend fun request(path: String, token: String = "", body: JSONObject? = null, image: Boolean = false): Any =
        withContext(Dispatchers.IO) {
            val address = runCatching { URL(base + path) }.getOrNull()
                ?: throw Hui798Exception("慧生活798连接配置缺失")
            if (address.protocol != "https" || address.userInfo != null) throw Hui798Exception("慧生活798连接配置异常")
            val connection = openConnection(address)
            try {
                connection.requestMethod = if (body == null) "GET" else "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("ApplicationType", "1,1")
                connection.setRequestProperty("VersionCode", "3.1.9")
                connection.setRequestProperty("User-Agent", "Android_ilife798_3.1.9")
                if (token.isNotBlank()) connection.setRequestProperty("Authorization", token)
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    val bytes = body.toString().toByteArray(Charsets.UTF_8)
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                if (status in setOf(401, 403)) throw Hui798Exception("慧生活798登录已失效，请重新登录", loginExpired = true)
                if (status !in 200..299) throw Hui798Exception("连接失败，请稍后重试")
                val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { it.readBytes() }
                    ?: throw Hui798Exception("服务器返回内容为空")
                ensureActive()
                if (image) return@withContext bytes.takeIf { it.isNotEmpty() } ?: throw Hui798Exception("图形验证码为空")
                val response = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                    ?: throw Hui798Exception("服务器返回内容异常")
                val code = response.opt("code")?.toString()?.toIntOrNull()
                    ?: throw Hui798Exception("服务器未返回请求结果")
                if (code != 0) {
                    val message = response.optString("msg").ifBlank { when (code) {
                        -88 -> "请先在慧生活798完成代扣签约"
                        -87 -> "代扣签约已过期，请在慧生活798重新签约"
                        -52 -> "账户欠费，请在慧生活798充值后使用"
                        -20 -> "请先在慧生活798绑定一卡通账号"
                        -19 -> "设备准备中，请稍后"
                        -82 -> "请先在慧生活798完成支付"
                        else -> "请求失败，请稍后重试"
                    } }
                    val expired = code in setOf(-99, -21)
                    throw Hui798Exception(if (expired) "慧生活798登录已失效，请重新登录" else message, expired, rejected = true)
                }
                response
            } finally { connection.disconnect() }
        }

    override suspend fun captcha(key: String): ByteArray = request("/captcha/?s=${encode(key)}&r=${System.currentTimeMillis()}", image = true) as ByteArray
    override suspend fun sendCode(phone: String, imageCode: String, key: String) {
        request("/acc/login/code", body = JSONObject().put("un", phone).put("authCode", imageCode).put("s", key))
    }
    override suspend fun login(phone: String, code: String): Hui798Account {
        if (salt.isBlank()) throw Hui798Exception("慧生活798连接配置缺失")
        val data = request("/acc/login?sign=${Hui798Protocol.loginSign(phone, code, salt)}",
            body = JSONObject().put("authCode", code).put("un", phone)) as JSONObject
        val login = data.optJSONObject("data")?.optJSONObject("al")
        val token = login?.optString("token").orEmpty()
        if (token.isBlank()) throw Hui798Exception("登录未返回账号信息，请重试")
        return Hui798Account(phone, token, login?.identifier("uid"))
    }
    override suspend fun device(id: String, token: String): Hui798Device {
        val data = request("/ui/app/dev/home/1?did=${encode(id)}&apply=6", token) as JSONObject
        val name = data.optJSONObject("data")?.optJSONObject("device")?.optString("name").orEmpty()
        return Hui798Device(id, name)
    }
    override suspend fun status(id: String, token: String): Hui798Reading {
        val data = request("/ui/app/dev/status?did=${encode(id)}&more=false", token) as JSONObject
        return reading(data.optJSONObject("data")?.optJSONObject("device"))
    }
    override suspend fun overview(token: String): Hui798Overview {
        val data = (request("/ui/app/master?ad=true", token) as JSONObject).optJSONObject("data")
        val userId = data?.optJSONObject("account")?.identifier("id")
            ?: throw Hui798Exception("暂时无法核对慧生活798账号，请重试")
        val devices = data.optJSONArray("favos") ?: org.json.JSONArray()
        return Hui798Overview(userId, (0 until devices.length()).mapNotNull { index ->
            val device = devices.optJSONObject(index) ?: return@mapNotNull null
            device.identifier("id")?.let { Hui798Device(it, device.optString("name")) }
        }.distinctBy { it.id })
    }
    override suspend fun deviceUse(id: String, token: String): Hui798DeviceUse {
        val data = (request("/ui/app/dev/home/1?did=${encode(id)}&apply=6", token) as JSONObject).optJSONObject("data")
        val device = data?.optJSONObject("device")
        val returnedId = device?.identifier("id")
        if (returnedId != null && returnedId != id) throw Hui798Exception("设备信息不一致，请重新扫码")
        // Home can report status 10 while /dev/status reports this same order as running (1).
        // Use the live status for lifecycle decisions and bind home's user to its matching marker.
        val current = status(id, token)
        val homeTime = device?.optJSONObject("gene")?.opt("time")?.toString()?.toLongOrNull()?.takeIf { it > 0 }
        if (current.status == 1 && homeTime != current.sessionTime)
            throw Hui798Exception("设备用水记录尚未同步，请刷新")
        return Hui798DeviceUse(Hui798Device(id, device?.optString("name").orEmpty()), current,
            data?.optJSONObject("user")?.identifier("id"))
    }
    private fun reading(device: JSONObject?): Hui798Reading {
        val gene = device?.optJSONObject("gene")
            ?: throw Hui798Exception("暂时无法确认设备状态，请刷新")
        val status = gene.opt("status")?.toString()?.toIntOrNull()
            ?: throw Hui798Exception("暂时无法确认设备状态，请刷新")
        if (status !in setOf(1, 99)) throw Hui798Exception("暂时无法确认设备状态，请刷新")
        val time = gene.opt("time")?.toString()?.toLongOrNull()?.takeIf { it > 0 }
        if (status == 1 && time == null) throw Hui798Exception("设备未返回用水记录，请核对现场状态")
        return Hui798Reading(status, time)
    }
    private fun JSONObject.identifier(key: String): String? = opt(key)?.takeIf { it is String || it is Number }
        ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    override suspend fun start(id: String, token: String) { request("/dev/start?did=${encode(id)}&upgrade=true&ptype=91&rcp=false", token) }
    override suspend fun end(id: String, token: String) { request("/dev/end?did=${encode(id)}&rcp=false", token) }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
