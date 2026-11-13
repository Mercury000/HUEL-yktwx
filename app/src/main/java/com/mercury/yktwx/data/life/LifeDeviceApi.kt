package com.mercury.yktwx.data.life

import kotlinx.serialization.json.*
import com.mercury.yktwx.data.*
import com.mercury.yktwx.BuildConfig
import com.mercury.yktwx.data.AppHttp
import com.mercury.yktwx.data.readBytesAtMost
import com.mercury.yktwx.data.readResponse
import java.security.MessageDigest
import okhttp3.Call

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
    fun message(data: JsonObject): String? = listOf("faultDesc", "deviceErrorMsg", "msg", "message", "_officialResponseMessage")
        .firstNotNullOfOrNull { data.text(it).takeIf(String::isNotBlank) }
}

/** Shared account and device transport for drinking water, showers and laundry. */
class LifeDeviceApi(
    private val secret: String = BuildConfig.PANGGUI_API_SECRET,
    private val client: Call.Factory,
    private val clock: () -> Long = System::currentTimeMillis,
) : LifeDeviceService, LifeLaundryService, LifeAccountService {
    private suspend fun postEnvelope(path: String, fields: Map<String, String>, token: String = ""): JsonObject {
        if (token.isNotBlank() && secret.isBlank()) throw LifeDeviceException("胖乖生活连接配置缺失")
        val timestamp = clock().toString()
        val request = AppHttp.request("https://userapi.qiekj.com$path")
            .header("Version", LifeDeviceProtocol.Version)
            .header("channel", LifeDeviceProtocol.Channel)
            // Preserve the official wire identity required by this service.
            .header("User-Agent", "okhttp/3.14.9")
            .header("timestamp", timestamp)
        android.os.Build.BRAND?.let { request.header("phoneBrand", it) }
        if (token.isNotBlank()) {
            request.header("token", token).header("Authorization", token)
                .header("sign", LifeDeviceProtocol.sign(secret, timestamp, path, token))
        }
        request.post(AppHttp.form(fields + if (token.isNotBlank()) mapOf("token" to token) else emptyMap(),
            "application/x-www-form-urlencoded;charset=UTF-8"))
        return client.readResponse(request.build()) { result ->
            val status = result.code
            val bytes = result.readBytesAtMost(1_048_576)
            if (bytes.size > 1_048_576) throw LifeDeviceException("服务器返回内容过大")
            val text = bytes.toString(Charsets.UTF_8)
            val response = runCatching { parseObject(text) }.getOrElse {
                throw LifeDeviceException("$path：${if (status !in 200..299) "HTTP $status，" else ""}响应不是 JSON 对象",
                    businessRejected = status in setOf(401, 403))
            }
            val code = response.text("code")
            if (code != "0" || status !in 200..299) {
                val message = response.text("msg").takeIf(String::isNotBlank)
                    ?: response.text("message").takeIf(String::isNotBlank)
                    ?: "$path：服务端未提供原因（code=${code.ifBlank { "缺失" }}，HTTP $status）"
                // Official 1.145.0 ResultStateKt.paresResult(); see docs/life-login-expiry-evidence.md.
                val expired = code in setOf("10", "126")
                throw LifeDeviceException(message, expired,
                    businessRejected = code.toIntOrNull()?.let { it != 0 } == true || status in setOf(401, 403))
            }
            response
        }
    }
    private suspend fun post(path: String, fields: Map<String, String>, token: String = ""): Any? {
        val response = postEnvelope(path, fields, token)
        val data = response.scalarOrElement("data").takeUnless { it == JsonNull }
        val message = response.text("msg").takeIf(String::isNotBlank)
        return if (data is JsonObject && message != null) data.withField("_officialResponseMessage", message) else data
    }

    override suspend fun sendCode(phone: String) {
        post("/common/sms/sendCode", mapOf("phone" to phone, "template" to "reg"))
    }
    override suspend fun profile(token: String): LifeAccountProfile {
        val data = post("/user/info", emptyMap(), token) as? JsonObject
            ?: throw LifeDeviceException("暂时无法读取胖乖账户，请重试")
        val id = data.text("id")
        val phone = data.text("phone")
        if (id.isBlank() || !phone.matches(Regex("1[0-9]{10}")))
            throw LifeDeviceException("胖乖账户信息不完整，请重试")
        return LifeAccountProfile(id, phone)
    }
    override suspend fun login(phone: String, code: String): LifeAccount {
        val data = post("/user/reg", mapOf("channel" to LifeDeviceProtocol.Channel, "phone" to phone, "verify" to code)) as? JsonObject
        val token = data?.text("token").orEmpty()
        if (token.isBlank()) throw LifeDeviceException("登录未返回账号信息，请重试")
        return LifeAccount(phone, token)
    }
    override suspend fun device(serial: String, token: String): LifeDevice {
        return readDevice(serial, "SN", setOf("04", "08"), token)
    }
    override suspend fun laundryDevice(code: String, token: String): LifeDevice = readDevice(code, "NQT", setOf("00"), token)
    private suspend fun readDevice(code: String, field: String, categories: Set<String>, token: String): LifeDevice {
        val scan = post("/goods/scan/v2", mapOf(field to code), token).obj()
        val category = scan.text("categoryCode")
        if (scan.text("type") != "goods" || category !in categories)
            throw LifeDeviceException(LifeDeviceProtocol.message(scan) ?: "当前应用尚未接入此设备类别（categoryCode=$category）")
        val id = scan.required("id")
        val detail = post("/goods/normal/details", mapOf("goodsId" to id), token).obj()
        if (detail.required("goodsId") != id || detail.text("categoryCode") != category) throw LifeDeviceException("设备信息不匹配，请重新扫码")
        val deviceError = detail.text("deviceErrorMsg").takeIf(String::isNotBlank)
        val offlineBluetoothAfterPay = detail.text("deviceErrorCode") == "4204" &&
            detail.text("isBluetooth") == "1" && detail.text("payment") != "before"
        // LockingAct checks this before loading programs/channels, except its offline BLE path.
        if (deviceError != null && !offlineBluetoothAfterPay) throw LifeDeviceException(deviceError, businessRejected = true)
        val skuData = post("/goods/normal/skus", mapOf("goodsId" to id), token) as? JsonArray
            ?: throw LifeDeviceException("设备未返回接水选项")
        // Official scanAvailable selection checks apply to washer programs, not water outlets.
        val skus = skuData.objects().map { LifeSku(it.required("skuId"), it.text("name"), if (category == "00") it.booleanOrNull("scanAvailable") else null,
            it.text("price"), it.text("feature"), if (category == "00")
                (it.text("markMinutes").toBigDecimalOrNull() ?: it.text("unit").toBigDecimalOrNull())
                    ?.let { value -> runCatching { value.intValueExact() }.getOrNull() }?.takeIf { minutes -> minutes > 0 }
                else null) }
        val orgId = detail.required("orgId")
        val routeShopId = scan.required("shopId")
        val shop = post("/shop/outline/v2", mapOf("orgId" to orgId, "goodsId" to id), token).obj()
        val imei = detail.required("imei")
        val channels = if (category in setOf("04", "08")) post("/payChannelRoute/afterPay", mapOf("imei" to imei, "useTicket" to "0", "shopId" to routeShopId, "goodsCategory" to category), token).channels()
            else emptyList()
        return LifeDevice(id, code, detail.text("name"), detail.text("categoryCode"), routeShopId, orgId,
            detail.text("shopId"), imei, shop.text("shopName"), shop.text("address"), detail.text("payment"),
            when (detail.text("isBluetooth")) { "0" -> false; "1" -> true; else -> null }, detail.booleanOrNull("supportAppUserFinish"),
            deviceError.takeUnless { offlineBluetoothAfterPay }, skus, channels)
    }
    override suspend fun selectAfterPay(token: String) { post("/payChannelRoute/addUserAfterPayChannel", mapOf("method" to "15"), token) }
    override suspend fun unlock(skuId: String, token: String): WaterUnlock {
        return unlockAt("/goods/water/unlock", skuId, token)
    }
    override suspend fun unlockShower(skuId: String, token: String): WaterUnlock = unlockAt("/goods/shower/unlock", skuId, token)
    private suspend fun unlockAt(path: String, skuId: String, token: String): WaterUnlock {
        val data = post(path, mapOf("skuId" to skuId, "promotions" to LifeDeviceProtocol.Promotions), token).obj()
        return WaterUnlock(data.required("msgId"), data.required("orderNo"))
    }
    override suspend fun message(messageId: String, token: String): WaterMessage? {
        val data = post("/machine/msg/sync", mapOf("msgId" to messageId), token) ?: return null
        return data.obj().let { WaterMessage(it.text("state"), it.text("code")) }
    }
    override suspend fun water(skuId: String, token: String): WaterReading {
        return readingAt("/goods/water/sync", skuId, token)
    }
    override suspend fun shower(skuId: String, token: String): WaterReading = readingAt("/goods/shower/sync", skuId, token)
    private suspend fun readingAt(path: String, skuId: String, token: String): WaterReading {
        val data = post(path, mapOf("skuId" to skuId), token).obj()
        val amount = data.text("amount").toBigDecimalOrNull()
        return WaterReading(data.text("status").toIntOrNull(), data.text("workStatus").toIntOrNull(), data.text("identify"),
            amount?.signum() == 0, amount?.signum() == 1)
    }
    override suspend fun endWater(goodsId: String, token: String) {
        endAt("/goods/endWater", mapOf("goodsId" to goodsId), token)
    }
    override suspend fun finishSupported(goodsId: String, token: String): Boolean? =
        post("/goods/normal/details", mapOf("goodsId" to goodsId), token).obj().let {
            if (it.required("goodsId") != goodsId) throw LifeDeviceException("设备信息不匹配，请重新扫码")
            it.booleanOrNull("supportAppUserFinish")
        }
    override suspend fun endShower(goodsId: String, orderNo: String, token: String) {
        endAt("/shower/goods/remoteEnd", mapOf("goodsId" to goodsId, "orderNo" to orderNo), token)
    }
    private suspend fun endAt(path: String, fields: Map<String, String>, token: String) {
        val response = postEnvelope(path, fields, token)
        // DeviceStartupAct.afterEndData -> lambda40 also checks this Boolean.
        if (response.scalarOrElement("data") != true) throw LifeDeviceException(
            LifeDeviceProtocol.message(response) ?: "$path：data=${response["data"] ?: "缺失"}，服务端未提供原因")
    }
    override suspend fun createAfterPay(orderNo: String, token: String): String {
        val data = post("/order/afterPay/creating", mapOf("orderNo" to orderNo), token).obj()
        if (data.required("orderNo") != orderNo) throw LifeDeviceException("用水订单信息不匹配")
        return data.required("orderId")
    }
    override suspend fun syncOrder(orderNo: String, token: String) {
        val data = post("/order/sync", mapOf("orderNo" to orderNo, "payType" to "0"), token).obj()
        // PayStateAct retries nonzero codes; these are not a permanent business rejection.
        if (data.text("code") != "0") throw LifeDeviceException(LifeDeviceProtocol.message(data)
            ?: "/order/sync：订单尚未确认（code=${data.text("code").ifBlank { "缺失" }}），服务端未提供原因")
    }

    private fun laundryItems(deviceId: String, skuId: String) = jsonArrayOf(listOf(jsonObjectOf("amount" to ("1"), "goodsId" to (deviceId), "num" to ("1"), "skuId" to (skuId), "soldType" to ("1")))).toString()
    override suspend fun laundryQuote(device: LifeDevice, skuId: String, token: String): LaundryQuote {
        val data = post("/trade/preview/v4", mapOf("items" to laundryItems(device.id, skuId), "promotions" to "[]",
            "isAutoSelectPromotion" to "0", "forbiddenPromotions" to "[]"), token).obj()
        val item = (data.scalarOrElement("items") as? JsonArray)?.objects()?.singleOrNull()
            ?: throw LifeDeviceException("设备未返回程序价格")
        if (item.required("goodsId") != device.id || item.required("skuId") != skuId)
            throw LifeDeviceException("程序信息不匹配，请重新选择")
        val price = data.required("realPrice").validPrice()
        val channels = post("/payChannelRoute/prePay", mapOf("imei" to device.imei, "goodsId" to device.id,
            "hitAd" to "0", "payPrice" to price, "useTicket" to "0", "shopId" to device.routeShopId,
            "combineOrderStatus" to "false", "confirmSelectedStatus" to "false"), token).channels()
        val rawInfo = item.scalarOrElement("skuInfo")
        val info = rawInfo as? JsonObject ?: (rawInfo as? String)?.let { runCatching { parseObject(it) }.getOrNull() }
        val minutes = info?.text("markMinutes")?.toIntOrNull()?.takeIf { it > 0 }
        return LaundryQuote(device.id, skuId, price, minutes, channels)
    }
    override suspend fun checkLaundryDevice(deviceId: String, token: String): String {
        val result = post("/trade/check_device_status", mapOf("goodsId" to deviceId), token)
        if (result == null || result == JsonNull) return ""
        val data = result.obj()
        if (data.booleanOrNull("health") == false) throw LifeDeviceException(LifeDeviceProtocol.message(data)
            ?: "/trade/check_device_status：health=false，服务端未提供原因")
        return data.text("msgId")
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
        val item = (data.scalarOrElement("tradeOrderItem") as? JsonArray)?.objects()?.singleOrNull()
            ?: throw LifeDeviceException("订单未返回洗衣程序，请在胖乖生活核对")
        val rawInfo = item.scalarOrElement("skuInfo")
        val info = rawInfo as? JsonObject ?: (rawInfo as? String)?.let { runCatching { parseObject(it) }.getOrNull() }
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
    override suspend fun prepareLaundryPayment(orderNo: String, method: String, token: String): String {
        if (method !in setOf("13", "52")) throw LifeDeviceException("该支付方式暂未接入")
        val data = post("/pay/unify/prePay", mapOf("orderNo" to orderNo, "payType" to method), token).obj()
        return data.required("prepayParam")
    }
    override suspend fun laundryPaid(orderNo: String, method: String, token: String): Boolean =
        post("/order/pay_status", mapOf("orderNo" to orderNo, "payType" to method), token) == true
    override suspend fun laundryStarted(orderNo: String, token: String): Boolean =
        post("/machine/device_is_start", mapOf("orderNo" to orderNo), token) == true
}

internal fun JsonObject.text(name: String): String = scalarOrElement(name)?.takeUnless { it == JsonNull }?.toString().orEmpty()
internal fun JsonObject.booleanOrNull(name: String): Boolean? = when (text(name).lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}
private fun JsonObject.required(name: String) = text(name).takeIf { it.isNotBlank() } ?: throw LifeDeviceException("响应缺少 $name")
private fun Any?.obj() = this as? JsonObject ?: throw LifeDeviceException("响应的 data 不是 JSON 对象")
internal fun JsonArray.objects(): List<JsonObject> = indices.map { objectValue(it) }
private fun Any?.channels(): List<LifePayChannel> = (this as? JsonArray ?: throw LifeDeviceException("设备未返回支付方式"))
    .objects().map { LifePayChannel(it.required("method"), it.text("methodName"), it.booleanOrNull("canChecked"),
        it.booleanOrNull("needOpenFlag"), it.text("remark").ifBlank { it.text("notUseRemark") }.ifBlank { null }, it.text("link").ifBlank { null }) }
private fun String.validPrice(): String = takeIf { toBigDecimalOrNull()?.let { price -> price.signum() >= 0 && price.scale() <= 2 } == true }
    ?: throw LifeDeviceException("价格信息异常，请在胖乖生活核对")
internal fun lifeTimestamp(value: String): Long? = runCatching {
    java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        .atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
}.getOrNull()
