package com.mercury.yktwx.data.life

import kotlinx.serialization.json.*
import com.mercury.yktwx.data.*
import com.mercury.yktwx.BuildConfig
import com.mercury.yktwx.data.AppHttp
import com.mercury.yktwx.data.readBytesAtMost
import com.mercury.yktwx.data.readResponse
import java.net.URI
import java.security.MessageDigest
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

internal object Hui798Protocol {
    fun loginSign(phone: String, code: String, salt: String): String = MessageDigest.getInstance("MD5")
        .digest("authCode=$code&un=$phone$salt".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Only the 798 wire protocol; presentation and device lifecycle live in our own app. */
class Hui798Api(
    gateway: String = BuildConfig.HUI798_API_GATEWAY,
    private val salt: String = BuildConfig.HUI798_SIGN_SALT,
    private val client: Call.Factory,
) : Hui798Service {
    private val base = gateway.trim().trimEnd('/')
    private val hasUserInfo = runCatching { URI(base).rawUserInfo != null }.getOrDefault(true)
    private suspend fun request(path: String, token: String = "", body: JsonObject? = null, image: Boolean = false, query: Map<String, String> = emptyMap()): Any {
        val address = (base + path).toHttpUrlOrNull()?.newBuilder()?.apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }?.build() ?: throw Hui798Exception("慧生活798连接配置缺失")
        if (!address.isHttps || hasUserInfo)
            throw Hui798Exception("慧生活798连接配置异常")
        val request = AppHttp.request(address.toString())
            .header("ApplicationType", "1,1")
            .header("VersionCode", "3.1.9")
            .header("User-Agent", "Android_ilife798_3.1.9")
        if (token.isNotBlank()) request.header("Authorization", token)
        if (body != null) request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        return client.readResponse(request.build()) { result ->
            val status = result.code
            if (status in setOf(401, 403)) throw Hui798Exception("慧生活798登录已失效，请重新登录", loginExpired = true)
            if (status !in 200..299) throw Hui798Exception("连接失败，请稍后重试")
            val limit = if (image) 2_097_152 else 1_048_576
            val bytes = result.readBytesAtMost(limit)
            if (bytes.size > limit) throw Hui798Exception("服务器返回内容过大")
            if (image) return@readResponse bytes.takeIf { it.isNotEmpty() } ?: throw Hui798Exception("图形验证码为空")
            val response = runCatching { parseObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                ?: throw Hui798Exception("服务器返回内容异常")
            val code = response.scalarOrElement("code")?.toString()?.toIntOrNull()
                ?: throw Hui798Exception("服务器未返回请求结果")
            if (code != 0) {
                val message = response.stringOrDefault("msg").ifBlank { when (code) {
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
        }
    }

    override suspend fun captcha(key: String): ByteArray = request("/captcha/", image = true, query = mapOf("s" to key, "r" to System.currentTimeMillis().toString())) as ByteArray
    override suspend fun sendCode(phone: String, imageCode: String, key: String) {
        request("/acc/login/code", body = jsonObjectOf("un" to (phone), "authCode" to (imageCode), "s" to (key)))
    }
    override suspend fun login(phone: String, code: String): Hui798Account {
        if (salt.isBlank()) throw Hui798Exception("慧生活798连接配置缺失")
        val data = request("/acc/login", query = mapOf("sign" to Hui798Protocol.loginSign(phone, code, salt)),
            body = jsonObjectOf("authCode" to (code), "un" to (phone))) as JsonObject
        val login = data.objectOrNull("data")?.objectOrNull("al")
        val token = login?.stringOrDefault("token").orEmpty()
        if (token.isBlank()) throw Hui798Exception("登录未返回账号信息，请重试")
        return Hui798Account(phone, token, login?.identifier("uid"))
    }
    override suspend fun accountStatus(token: String): String {
        val response = request("/acc/stat", token) as JsonObject
        return response.objectOrNull("data")?.objectOrNull("al")?.identifier("uid")
            ?: throw Hui798Exception("账号状态未返回账号信息，请下拉刷新后重试")
    }
    override suspend fun device(id: String, token: String): Hui798Device {
        val data = request("/ui/app/dev/home/1", token, query = mapOf("did" to id, "apply" to "6")) as JsonObject
        val name = data.objectOrNull("data")?.objectOrNull("device")?.stringOrDefault("name").orEmpty()
        return Hui798Device(id, name)
    }
    override suspend fun status(id: String, token: String): Hui798Reading {
        val data = request("/ui/app/dev/status", token, query = mapOf("did" to id, "more" to "false")) as JsonObject
        return reading(data.objectOrNull("data")?.objectOrNull("device"))
    }
    override suspend fun overview(token: String): Hui798Overview {
        val data = (request("/ui/app/master", token, query = mapOf("ad" to "true")) as JsonObject).objectOrNull("data")
        val userId = data?.objectOrNull("account")?.identifier("id")
            ?: throw Hui798Exception("暂时无法读取用水设备，请刷新")
        val devices = data.arrayOrNull("favos") ?: jsonArrayOf()
        return Hui798Overview(userId, (0 until devices.size).mapNotNull { index ->
            val device = devices.objectOrNull(index) ?: return@mapNotNull null
            device.identifier("id")?.let { Hui798Device(it, device.stringOrDefault("name")) }
        }.distinctBy { it.id })
    }
    override suspend fun deviceUse(id: String, token: String): Hui798DeviceUse {
        val data = (request("/ui/app/dev/home/1", token, query = mapOf("did" to id, "apply" to "6")) as JsonObject).objectOrNull("data")
        val device = data?.objectOrNull("device")
        val returnedId = device?.identifier("id")
        if (returnedId != null && returnedId != id) throw Hui798Exception("设备信息不一致，请重新扫码")
        // Home can report status 10 while /dev/status reports this same order as running (1).
        // Use the live status for lifecycle decisions and bind home's user to its matching marker.
        val current = status(id, token)
        val homeTime = device?.objectOrNull("gene")?.scalarOrElement("time")?.toString()?.toLongOrNull()?.takeIf { it > 0 }
        if (current.status == 1 && homeTime != current.sessionTime)
            throw Hui798Exception("设备用水记录尚未同步，请刷新")
        return Hui798DeviceUse(Hui798Device(id, device?.stringOrDefault("name").orEmpty()), current,
            data?.objectOrNull("user")?.identifier("id"))
    }
    private fun reading(device: JsonObject?): Hui798Reading {
        val gene = device?.objectOrNull("gene")
            ?: throw Hui798Exception("暂时无法确认设备状态，请刷新")
        val status = gene.scalarOrElement("status")?.toString()?.toIntOrNull()
            ?: throw Hui798Exception("暂时无法确认设备状态，请刷新")
        if (status !in setOf(1, 99)) throw Hui798Exception("暂时无法确认设备状态，请刷新")
        val time = gene.scalarOrElement("time")?.toString()?.toLongOrNull()?.takeIf { it > 0 }
        if (status == 1 && time == null) throw Hui798Exception("设备未返回用水记录，请核对现场状态")
        return Hui798Reading(status, time)
    }
    private fun JsonObject.identifier(key: String): String? = scalarOrElement(key)?.takeIf { it is String || it is Number }
        ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
    override suspend fun start(id: String, token: String) { request("/dev/start", token, query = mapOf("did" to id, "upgrade" to "true", "ptype" to "91", "rcp" to "false")) }
    override suspend fun end(id: String, token: String) { request("/dev/end", token, query = mapOf("did" to id, "rcp" to "false")) }
}
