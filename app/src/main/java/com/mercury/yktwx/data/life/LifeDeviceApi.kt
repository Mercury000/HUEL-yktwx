package com.mercury.yktwx.data.life

import com.mercury.yktwx.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

internal object LifeDeviceProtocol {
    const val Version = "1.109.1"
    const val Channel = "android_app"
    // The capture disables assets of all three observed kinds; do not enable discounts implicitly.
    const val Promotions = """[{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-6"},{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-7"},{"assetId":"0","oldPromotionId":"0","orgId":"0","promotionId":"0","promotionType":"-8"}]"""
    fun sign(secret: String, timestamp: String, path: String, token: String): String {
        val raw = "appSecret=$secret&channel=$Channel&timestamp=$timestamp&token=$token&version=$Version&$path"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/** Shared account, scan, device and channel transport for water and future laundry support. */
class LifeDeviceApi(
    private val secret: String = BuildConfig.PANGGUI_API_SECRET,
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val clock: () -> Long = System::currentTimeMillis,
) : LifeDeviceService {
    private suspend fun post(path: String, fields: Map<String, String>, token: String = ""): Any? = withContext(Dispatchers.IO) {
        if (token.isNotBlank() && secret.isBlank()) throw LifeDeviceException("胖乖生活连接配置缺失")
        val connection = openConnection(URL("https://userapi.qiekj.com$path"))
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            connection.setRequestProperty("Version", LifeDeviceProtocol.Version)
            connection.setRequestProperty("channel", LifeDeviceProtocol.Channel)
            connection.setRequestProperty("phoneBrand", android.os.Build.BRAND)
            connection.setRequestProperty("User-Agent", "okhttp/3.14.9")
            val timestamp = clock().toString()
            connection.setRequestProperty("timestamp", timestamp)
            if (token.isNotBlank()) {
                connection.setRequestProperty("token", token)
                connection.setRequestProperty("Authorization", token)
                connection.setRequestProperty("sign", LifeDeviceProtocol.sign(secret, timestamp, path, token))
            }
            val form = (fields + if (token.isNotBlank()) mapOf("token" to token) else emptyMap()).entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(form.size)
            connection.outputStream.use { it.write(form) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            ensureActive()
            if (status in setOf(401, 403)) throw LifeDeviceException("胖乖登录已失效，请重新登录", true)
            if (status !in 200..299) throw LifeDeviceException("连接失败，请稍后重试")
            val response = runCatching { JSONObject(text) }.getOrElse { throw LifeDeviceException("服务器返回内容异常") }
            val code = response.text("code")
            if (code !in setOf("0", "200")) {
                val message = response.text("msg").ifBlank { response.text("message") }.ifBlank { "请求失败，请稍后重试" }
                val expired = code in setOf("401", "403") || listOf("未登录", "未登陆", "请先登录", "登录失效", "登录过期").any(message::contains) ||
                    message.contains("token", true) && listOf("过期", "无效", "expired", "invalid").any { message.contains(it, true) }
                throw LifeDeviceException(if (expired) "胖乖登录已失效，请重新登录" else message, expired)
            }
            response.opt("data").takeUnless { it == JSONObject.NULL }
        } finally { connection.disconnect() }
    }

    override suspend fun sendCode(phone: String) {
        post("/common/sms/sendCode", mapOf("phone" to phone, "template" to "reg"))
    }
    override suspend fun login(phone: String, code: String): LifeAccount {
        val data = post("/user/reg", mapOf("channel" to LifeDeviceProtocol.Channel, "phone" to phone, "verify" to code)) as? JSONObject
        val token = data?.text("token").orEmpty()
        if (token.isBlank()) throw LifeDeviceException("登录未返回账号信息，请重试")
        return LifeAccount(phone, token)
    }
    override suspend fun device(serial: String, token: String): LifeDevice {
        val scan = post("/goods/scan/v2", mapOf("SN" to serial), token).obj()
        if (scan.text("type") != "goods" || scan.text("categoryCode") != "04") throw LifeDeviceException("请扫描饮水设备")
        val id = scan.required("id")
        val detail = post("/goods/normal/details", mapOf("goodsId" to id), token).obj()
        if (detail.required("goodsId") != id || detail.text("categoryCode") != "04") throw LifeDeviceException("设备信息不匹配，请重新扫码")
        val skuData = post("/goods/normal/skus", mapOf("goodsId" to id), token) as? JSONArray
            ?: throw LifeDeviceException("设备未返回接水选项")
        val skus = skuData.objects().map { LifeSku(it.required("skuId"), it.text("name"), it.optBoolean("scanAvailable", false) && it.text("stockState") == "1") }
        val orgId = detail.required("orgId")
        val routeShopId = scan.required("shopId")
        val shop = post("/shop/outline/v2", mapOf("orgId" to orgId, "goodsId" to id), token).obj()
        val imei = detail.required("imei")
        val channelData = post("/payChannelRoute/afterPay", mapOf("imei" to imei, "useTicket" to "0", "shopId" to routeShopId, "goodsCategory" to "04"), token) as? JSONArray
            ?: throw LifeDeviceException("设备未返回支付方式")
        val channels = channelData.objects().map {
            LifePayChannel(it.required("method"), it.text("methodName"), it.optBoolean("useStatus", false),
                it.optBoolean("needOpenFlag", true), it.text("notUseRemark").ifBlank { null }, it.text("link").ifBlank { null })
        }
        return LifeDevice(id, serial, detail.text("name"), detail.text("categoryCode"), routeShopId, orgId,
            detail.text("shopId"), imei, shop.text("shopName"), shop.text("address"), detail.text("payment"),
            detail.text("isBluetooth") != "0", detail.optBoolean("supportAppUserFinish", false),
            detail.text("deviceErrorMsg").ifBlank { null }, skus, channels)
    }
    override suspend fun selectAfterPay(token: String) { post("/payChannelRoute/addUserAfterPayChannel", mapOf("method" to "15"), token) }
    override suspend fun unlock(skuId: String, token: String): WaterUnlock {
        val data = post("/goods/water/unlock", mapOf("skuId" to skuId, "promotions" to LifeDeviceProtocol.Promotions), token).obj()
        return WaterUnlock(data.required("msgId"), data.required("orderNo"))
    }
    override suspend fun message(messageId: String, token: String): WaterMessage? {
        val data = post("/machine/msg/sync", mapOf("msgId" to messageId), token) ?: return null
        return data.obj().let { WaterMessage(it.text("state"), it.text("code")) }
    }
    override suspend fun water(skuId: String, token: String): WaterReading {
        val data = post("/goods/water/sync", mapOf("skuId" to skuId), token).obj()
        return WaterReading(data.text("status").toIntOrNull(), data.text("workStatus").toIntOrNull(), data.text("identify"),
            data.text("amount").toBigDecimalOrNull()?.signum() == 0)
    }
    override suspend fun endWater(goodsId: String, token: String) {
        if (post("/goods/endWater", mapOf("goodsId" to goodsId), token) != true) throw LifeDeviceException("结束接水失败，请重试")
    }
    override suspend fun createAfterPay(orderNo: String, token: String): String {
        val data = post("/order/afterPay/creating", mapOf("orderNo" to orderNo), token).obj()
        if (data.required("orderNo") != orderNo) throw LifeDeviceException("接水订单信息不匹配")
        return data.required("orderId")
    }
    override suspend fun syncOrder(orderNo: String, token: String) {
        val data = post("/order/sync", mapOf("orderNo" to orderNo, "payType" to "0"), token).obj()
        if (data.text("code") != "0") throw LifeDeviceException("订单处理失败，请稍后重试")
    }
}

internal fun JSONObject.text(name: String): String = opt(name)?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
private fun JSONObject.required(name: String) = text(name).takeIf { it.isNotBlank() } ?: throw LifeDeviceException("设备响应信息不完整")
private fun Any?.obj() = this as? JSONObject ?: throw LifeDeviceException("服务器返回内容异常")
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
