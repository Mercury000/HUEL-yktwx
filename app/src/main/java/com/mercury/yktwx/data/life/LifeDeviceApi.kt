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
) : LifeDeviceService, LifeLaundryService, LifeAccountService {
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
    override suspend fun profile(token: String): LifeAccountProfile {
        val data = post("/user/info", emptyMap(), token) as? JSONObject
            ?: throw LifeDeviceException("暂时无法读取胖乖账户，请重试")
        val id = data.text("id")
        val phone = data.text("phone")
        if (id.isBlank() || !phone.matches(Regex("1[0-9]{10}")))
            throw LifeDeviceException("胖乖账户信息不完整，请重试")
        return LifeAccountProfile(id, phone)
    }
    override suspend fun login(phone: String, code: String): LifeAccount {
        val data = post("/user/reg", mapOf("channel" to LifeDeviceProtocol.Channel, "phone" to phone, "verify" to code)) as? JSONObject
        val token = data?.text("token").orEmpty()
        if (token.isBlank()) throw LifeDeviceException("登录未返回账号信息，请重试")
        return LifeAccount(phone, token)
    }
    override suspend fun device(serial: String, token: String): LifeDevice {
        return readDevice(serial, "SN", "04", token)
    }
    override suspend fun laundryDevice(code: String, token: String): LifeDevice = readDevice(code, "NQT", "00", token)
    private suspend fun readDevice(code: String, field: String, category: String, token: String): LifeDevice {
        val scan = post("/goods/scan/v2", mapOf(field to code), token).obj()
        if (scan.text("type") != "goods" || scan.text("categoryCode") != category)
            throw LifeDeviceException(if (category == "04") "请扫描饮水设备" else "请扫描洗衣设备")
        val id = scan.required("id")
        val detail = post("/goods/normal/details", mapOf("goodsId" to id), token).obj()
        if (detail.required("goodsId") != id || detail.text("categoryCode") != category) throw LifeDeviceException("设备信息不匹配，请重新扫码")
        val skuData = post("/goods/normal/skus", mapOf("goodsId" to id), token) as? JSONArray
            ?: throw LifeDeviceException("设备未返回接水选项")
        val skus = skuData.objects().map { LifeSku(it.required("skuId"), it.text("name"), it.optBoolean("scanAvailable", false) && it.text("stockState") == "1",
            it.text("price"), it.text("feature"), if (category == "00")
                (it.text("markMinutes").toBigDecimalOrNull() ?: it.text("unit").toBigDecimalOrNull())
                    ?.let { value -> runCatching { value.intValueExact() }.getOrNull() }?.takeIf { minutes -> minutes > 0 }
                else null) }
        val orgId = detail.required("orgId")
        val routeShopId = scan.required("shopId")
        val shop = post("/shop/outline/v2", mapOf("orgId" to orgId, "goodsId" to id), token).obj()
        val imei = detail.required("imei")
        val channels = if (category == "04") post("/payChannelRoute/afterPay", mapOf("imei" to imei, "useTicket" to "0", "shopId" to routeShopId, "goodsCategory" to "04"), token).channels()
            else emptyList()
        return LifeDevice(id, code, detail.text("name"), detail.text("categoryCode"), routeShopId, orgId,
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

    private fun laundryItems(deviceId: String, skuId: String) = JSONArray().put(JSONObject()
        .put("amount", "1").put("goodsId", deviceId).put("num", "1").put("skuId", skuId).put("soldType", "1")).toString()
    override suspend fun laundryQuote(device: LifeDevice, skuId: String, token: String): LaundryQuote {
        val data = post("/trade/preview/v4", mapOf("items" to laundryItems(device.id, skuId), "promotions" to "[]",
            "isAutoSelectPromotion" to "0", "forbiddenPromotions" to "[]"), token).obj()
        val item = (data.opt("items") as? JSONArray)?.objects()?.singleOrNull()
            ?: throw LifeDeviceException("设备未返回程序价格")
        if (item.required("goodsId") != device.id || item.required("skuId") != skuId)
            throw LifeDeviceException("程序信息不匹配，请重新选择")
        if ((data.opt("promotions") as? JSONArray)?.objects()?.any { it.optBoolean("isUsed", false) } == true)
            throw LifeDeviceException("该价格含优惠，请在胖乖生活确认")
        val price = data.required("realPrice").validPrice()
        val channels = post("/payChannelRoute/prePay", mapOf("imei" to device.imei, "goodsId" to device.id,
            "hitAd" to "0", "payPrice" to price, "useTicket" to "0", "shopId" to device.routeShopId,
            "combineOrderStatus" to "false", "confirmSelectedStatus" to "false"), token).channels()
        val rawInfo = item.opt("skuInfo")
        val info = rawInfo as? JSONObject ?: (rawInfo as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val minutes = info?.text("markMinutes")?.toIntOrNull()?.takeIf { it > 0 }
        return LaundryQuote(device.id, skuId, price, minutes, channels)
    }
    override suspend fun checkLaundryDevice(deviceId: String, token: String): String {
        val data = post("/trade/check_device_status", mapOf("goodsId" to deviceId), token).obj()
        if (!data.optBoolean("health", false)) throw LifeDeviceException(data.text("faultDesc").ifBlank { "设备暂不可用，请稍后重试" })
        return data.required("msgId")
    }
    override suspend fun laundryMachineReady(messageId: String, token: String): Boolean =
        post("/machine/sync", mapOf("msgId" to messageId), token) == true
    override suspend fun createLaundryOrder(deviceId: String, skuId: String, token: String): LaundryOrder {
        val data = post("/trade/create", mapOf("items" to laundryItems(deviceId, skuId), "promotions" to "[]"), token).obj()
        return LaundryOrder(data.required("tradeNo"), data.required("orderId"))
    }
    override suspend fun cancelLaundryOrder(orderId: String, token: String) {
        post("/order/cancel", mapOf("orderId" to orderId), token)
    }
    override suspend fun laundryOrder(orderNo: String, token: String): LaundryReading {
        val data = post("/order/detail", mapOf("orderNo" to orderNo), token).obj()
        val item = (data.opt("tradeOrderItem") as? JSONArray)?.objects()?.singleOrNull()
            ?: throw LifeDeviceException("订单未返回洗衣程序，请在胖乖生活核对")
        val rawInfo = item.opt("skuInfo")
        val info = rawInfo as? JSONObject ?: (rawInfo as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
        val result = LaundryReading(data.required("orderNo"), data.required("id"), item.required("goodsId"), item.required("skuId"),
            data.text("orderStatus").toIntOrNull(), data.required("payPrice").validPrice(), data.text("machineFunctionName"),
            data.text("markMinutes").toIntOrNull()?.takeIf { it > 0 }, lifeTimestamp(data.text("payTime")),
            lifeTimestamp(data.text("completeTime")), lifeTimestamp(data.text("finishTime")), lifeTimestamp(data.text("createTime")),
            data.text("machineName"), data.text("shopName"), data.text("payTypeName"), info?.text("feature").orEmpty())
        if (result.orderNo != orderNo) throw LifeDeviceException("洗衣订单信息不匹配")
        return result
    }
    override suspend fun laundryChannels(session: LaundrySession, token: String): List<LifePayChannel> =
        post("/payChannelRoute/againPay", mapOf("orderNo" to session.orderNo, "confirmSelectedStatus" to "false",
            "shopId" to session.device.shopId), token).channels()
    override suspend fun payLaundryBalance(orderNo: String, token: String) {
        post("/pay/unify/pay", mapOf("orderNo" to orderNo, "payType" to "2"), token)
    }
    override suspend fun prepareLaundryAlipay(orderNo: String, token: String): String {
        val data = post("/pay/unify/prePay", mapOf("orderNo" to orderNo, "payType" to "13"), token).obj()
        return data.required("prepayParam")
    }
    override suspend fun laundryPaid(orderNo: String, method: String, token: String): Boolean =
        post("/order/pay_status", mapOf("orderNo" to orderNo, "payType" to method), token) == true
    override suspend fun laundryStarted(orderNo: String, token: String): Boolean =
        post("/machine/device_is_start", mapOf("orderNo" to orderNo), token) == true
}

internal fun JSONObject.text(name: String): String = opt(name)?.takeUnless { it == JSONObject.NULL }?.toString().orEmpty()
private fun JSONObject.required(name: String) = text(name).takeIf { it.isNotBlank() } ?: throw LifeDeviceException("设备响应信息不完整")
private fun Any?.obj() = this as? JSONObject ?: throw LifeDeviceException("服务器返回内容异常")
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun Any?.channels(): List<LifePayChannel> = (this as? JSONArray ?: throw LifeDeviceException("设备未返回支付方式"))
    .objects().map { LifePayChannel(it.required("method"), it.text("methodName"), it.optBoolean("useStatus", false),
        it.optBoolean("needOpenFlag", true), it.text("notUseRemark").ifBlank { null }, it.text("link").ifBlank { null }) }
private fun String.validPrice(): String = takeIf { toBigDecimalOrNull()?.let { price -> price.signum() >= 0 && price.scale() <= 2 } == true }
    ?: throw LifeDeviceException("价格信息异常，请在胖乖生活核对")
internal fun lifeTimestamp(value: String): Long? = runCatching {
    java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
}.getOrNull()
