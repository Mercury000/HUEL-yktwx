package com.mercury.yktwx.data.life

import android.graphics.BitmapFactory
import android.os.SystemClock
import com.mercury.yktwx.data.*
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Wire adapters only. Sources and the native/H5 boundary are recorded in docs/life-devices-hellobike.md. */
internal object HellobikeProtocol {
    // SHA-256 of the first signing certificate in the original 7.0.75 APK, Base64.DEFAULT (with LF).
    const val ApkSignHash = "zkrFhoDpIPlkKlif+uTNtL1EYctTSHr7vJ/GE641gE4=\n"
    const val LbsAppKey = "oho_ride_mobile_page"
    // Published by the official wait-pay H5, rather than the prototype's dummy signature.
    const val LbsSigningKey = "eabd21e6da6fa1c0f0d6621c2f498a7a"
    const val PaymentSdkVersion = "2.4.4"
    const val NativeVersion = "7.0.75"
    // Walle signing-block channel from the supplied, SHA-256 identified official APK.
    const val NativeChannel = "a0010005"
    // Official AppHelloMiniBrand HTML and scripts fetched on 2026-11-13.
    const val RideH5Version = "7.0.70"
    private val rideActions = setOf("user.tw.order.check", "user.ride.order.status",
        "tw.end.module.info", "tw.bike.unlock.page.basic.info", "user.ride.pre.ride", "user.ride.create", "ride.hub.pre.close")
    private val payActions = setOf("com.alphapay.getPayChannel", "com.alphapay.paymentOrder.pay", "com.alphapay.facade.orderSingleQuery")
    fun endpoint(action: String): String = when {
        action == "user.account.login" -> "https://api.hellobike.com/auth"
        action in setOf("user.account.sendCodeV3", "user.account.slideCaptcha") -> "https://api.hellobike.com/api"
        action == "hello.lbs.code.regeo" -> "https://hello-location-based-services.hellobike.com/api?$action"
        action == "user.artemis.pay.page.order.detail" -> "https://bike.hellobike.com/api"
        action == "user.ride.bikeCheck" -> "https://bike.hellobike.com/api"
        action in payActions -> "https://api.hellobike.com/api?$action"
        action in rideActions -> {
            val host = if (action.startsWith("user.tw.") || action == "user.ride.order.status") "ebike" else "bike"
            val path = when (action) {
                "user.ride.pre.ride", "tw.bike.unlock.page.basic.info" -> "/api/$action"
                "tw.end.module.info", "user.ride.create", "ride.hub.pre.close" -> "/api?$action"
                else -> "/api"
            }
            "https://$host.hellobike.com$path"
        }
        else -> throw HellobikeException("不支持的哈啰业务")
    }
    fun rideFields(account: HellobikeAccount) = jsonObjectOf("token" to account.token,
        "ticket" to account.ticket.takeIf(String::isNotBlank), "systemCode" to account.systemCode,
        "version" to NativeVersion, "h5Version" to RideH5Version, "appName" to "AppHelloMiniBrand",
        "from" to "h5", "sourceMarkup" to "ohoSaaS")
    // BikeRideCheckApiModel extends native BaseApiModel; it has no H5 or bizType fields.
    fun rideCheckFields(account: HellobikeAccount) = jsonObjectOf("token" to account.token,
        "ticket" to account.ticket.takeIf(String::isNotBlank), "systemCode" to account.systemCode,
        "version" to NativeVersion, "clientId" to account.clientId)
    fun paymentFields(account: HellobikeAccount) = jsonObjectOf("token" to account.token,
        "ticket" to account.ticket.takeIf(String::isNotBlank), "systemCode" to account.systemCode,
        "from" to "h5", "currentSdkVersion" to PaymentSdkVersion)
    fun lbsFields(position: HellobikePosition, timestamp: Long, signingKey: String = LbsSigningKey): JsonObject {
        val arg0 = jsonObjectOf("locations" to jsonArrayOf(listOf(jsonObjectOf("lat" to position.lat, "lng" to position.lng))), "extensions" to "all")
        val signature = MessageDigest.getInstance("MD5").digest((arg0.toString() + timestamp + signingKey).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return jsonObjectOf("appName" to "AppHelloBikeMainH5", "version" to "7.0.30", "releaseVersion" to "7.0.30",
            "from" to "h5", "systemCode" to "62", "arg0" to arg0.toString(), "method" to "reGeoCodingSearch",
            "arg1" to jsonObjectOf("appKey" to LbsAppKey, "signature" to signature, "src" to "SMALL_PROGRAM", "timestamp" to timestamp))
    }
    fun scanRequestId(userGuid: String, bikeNo: String, timestamp: Long): String {
        val date = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date(timestamp))
        return "${userGuid}_${bikeNo}_${date}_mini_${Random.nextInt(10_000).toString().padStart(4, '0')}"
    }
    // Official response text is user-facing data. Do not redact, truncate or rewrite it.
    // PreRideCreateResult uses cause/causeContent; P10 uses causeDesc. See the REA audit.
    fun message(data: JsonObject, includeEnvelope: Boolean = false): String? {
        fun lines(value: JsonObject, vararg keys: String) = keys.mapNotNull { value.hbText(it)?.takeIf(String::isNotBlank) }.distinct()
        val notices = data.arrayOrNull("notices").orEmpty().mapNotNull { it as? JsonObject }
            .flatMap { lines(it, "warnTitle", "warnContent") }
        val popup = data.objectOrNull("popup")?.let { lines(it, "title", "subTitle") }.orEmpty()
        val text = lines(data, "cause", "causeContent", "causeDesc") + popup + notices
        return text.distinct().takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: lines(data, "msg", "resultMessage").firstOrNull()
            ?: if (includeEnvelope) data.hbText("_officialResponseMessage")?.takeIf(String::isNotBlank) else null
    }
    fun missingReason(action: String, data: JsonObject): String =
        "$action：服务端未提供原因" + (data.hbInt("causeType")?.let { "（causeType=$it）" } ?: "")
    fun money(value: String): String? = value.toBigDecimalOrNull()?.let { it.setScale(maxOf(2, it.scale())).toPlainString() }
    fun closeReading(data: JsonObject, commit: Boolean = false): HellobikeCloseReading {
        val cause = data.hbInt("causeType") ?: throw HellobikeException("还车结果尚未确认，请刷新订单")
        val cloudMessage = message(data)
        // This endpoint is P10. Its H5 consumer branches on causeType, independently of state/status.
        val processing = cause in setOf(1101, 1108)
        if (cause == 1100) return HellobikeCloseReading(false, false, cloudMessage, completed = true)
        if (commit && processing) return HellobikeCloseReading(true, false, cloudMessage)
        if (processing) return HellobikeCloseReading(true, false, cloudMessage)
        // The type selects a transport. Accompanying keys/penalty text do not themselves
        // reject the server's standard P10 return result.
        val unsupported = data.hbInt("type")?.let { it != 1 } == true
        if (unsupported) throw HellobikeException(cloudMessage ?: missingReason("ride.hub.pre.close", data), officialRequired = true)
        if (cause in setOf(1102, 1103))
            return HellobikeCloseReading(false, true, cloudMessage, skipConfirmation = data.hbBoolean("skipConfirm") == true)
        val detail = cloudMessage ?: missingReason("ride.hub.pre.close", data)
        throw HellobikeException(detail, officialRequired = cause !in setOf(1104, 1105, 1106, 1107))
    }
}

class HellobikeApi internal constructor(
    private val client: Call.Factory,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val apkSignHash: String = HellobikeProtocol.ApkSignHash,
    private val userAgent: () -> String = { "" },
    private val ssid: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) : HellobikeService {
    private val loginSsid by lazy { ssid() }
    private var lastReturnOrder: String? = null
    private var lastReturnOperation: Int? = null
    @Synchronized private fun firstReturnOperation(account: HellobikeAccount, order: String, operation: Int): Int {
        val key = "${account.userGuid}:$order"
        if (lastReturnOrder != key) { lastReturnOrder = key; lastReturnOperation = null }
        val first = if (lastReturnOperation != null && lastReturnOperation != operation) 1 else 0
        lastReturnOperation = operation
        return first
    }
    private suspend fun request(action: String, fields: JsonObject): JsonObject {
        val body = fields.withField("action", action)
        val plain = action.startsWith("com.alphapay.") || action == "hello.lbs.code.regeo"
        val req = AppHttp.request(HellobikeProtocol.endpoint(action)).header("inner_action", action)
            .apply { userAgent().takeIf(String::isNotBlank)?.let { header("User-Agent", it) } }
            .post(body.toString().toRequestBody((if (plain) "text/plain; charset=utf-8" else "application/json; charset=utf-8").toMediaType())).build()
        return client.readResponse(req) { response ->
            val bytes = response.readBytesAtMost(3_145_728)
            if (bytes.size > 3_145_728) throw HellobikeException("哈啰返回内容过大")
            val envelope = runCatching { parseObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
                ?: throw HellobikeException(if (response.isSuccessful) "哈啰返回内容异常，请刷新状态" else "连接哈啰失败，请稍后重试")
            val code = envelope.hbInt("code")
            val resultStatus = envelope.hbInt("resultStatus").takeIf { action.startsWith("com.alphapay.") }
            val message = envelope.hbText("msg")?.takeIf(String::isNotBlank)
                ?: envelope.hbText("resultMessage")?.takeIf(String::isNotBlank)
                ?: HellobikeProtocol.message(envelope.objectOrNull("data") ?: envelope)
                ?: "$action：服务端未提供原因（code=$code）"
            // Official 7.0.75 HiResponse.isTokenError(); see docs/life-login-expiry-evidence.md.
            if (code in setOf(103, -10003)) throw HellobikeException(message, loginExpired = true, rejected = true)
            if (!response.isSuccessful) throw HellobikeException(message, rejected = true)
            // RidingOrderRepository.checkOrder returns null for a non-success response or absent data.
            // This is a ride-discovery result, not a command rejection or an expired account.
            if (action == "user.ride.bikeCheck" && code != null) {
                if (code != 0) return@readResponse JsonObject(emptyMap())
                if (envelope["data"] == null || envelope["data"] == JsonNull)
                    return@readResponse JsonObject(emptyMap())
            }
            if (code == null && resultStatus == null)
                throw HellobikeException(message)
            if (code != null && code != 0 || resultStatus != null && resultStatus != 0 || code == null && resultStatus != 0) {
                throw HellobikeException(message, rejected = true)
            }
            // The H5 ride adapters return data || {}. Missing data in an accepted
            // response is not an additional client-side business rejection.
            val data = envelope.objectOrNull("data") ?: if (action in rideActionsWithEmptyData)
                JsonObject(emptyMap()) else throw HellobikeException("$action：响应缺少 data")
            val responseMessage = envelope.hbText("msg") ?: envelope.hbText("resultMessage")
            if (responseMessage != null) data.withField("_officialResponseMessage", responseMessage) else data
        }
    }
    private val rideActionsWithEmptyData = setOf("user.ride.pre.ride", "user.ride.create", "user.ride.order.status",
        "tw.bike.unlock.page.basic.info", "user.tw.ride.check", "user.tw.order.check",
        "user.ride.pause.verify", "user.ride.pause", "user.ride.resume")
    private fun auth(phone: String, region: HellobikeRegion?, clientId: String): JsonObject {
        if (!phone.matches(Regex("1[0-9]{10}"))) throw HellobikeException("请输入正确的手机号")
        return jsonObjectOf("mobile" to phone, "systemCode" to "62", "version" to HellobikeProtocol.NativeVersion, "clientId" to clientId,
            "cityCode" to region?.takeIf { it.valid }?.cityCode, "adCode" to region?.takeIf { it.valid }?.adCode,
            "intlAreaCode" to "86", "locale" to "zh-CN")
    }
    override suspend fun sendCode(phone: String, region: HellobikeRegion?, clientId: String, offset: Int?, challenge: String?): HellobikeSmsResult {
        if (offset != null && offset !in 0..4096) throw HellobikeException("滑块位置无效，请刷新验证图片")
        val fields = auth(phone, region, clientId).withField("source", 0).withField("captcha", offset?.toString()).withField("challenge", challenge)
        val data = request("user.account.sendCodeV3", fields)
        // SwipeCaptchaManager.sendCodeV3 handles captchaType=1 before ok; the
        // checkSwipeCaptcha response succeeds only when ok is true.
        if (offset == null && data.hbInt("captchaType") == 1) return HellobikeSmsResult(true, data.hbText("challenge"))
        if (data.hbBoolean("ok") == true) return HellobikeSmsResult()
        throw HellobikeException(HellobikeProtocol.message(data, includeEnvelope = true)
            ?: HellobikeProtocol.missingReason("user.account.sendCodeV3", data), rejected = true)
    }
    override suspend fun slider(phone: String, region: HellobikeRegion?, clientId: String): HellobikeSlider {
        val data = request("user.account.slideCaptcha", auth(phone, region, clientId).withField("source", 0))
        fun decode(key: String): Pair<ByteArray, BitmapFactory.Options> {
            val encoded = data.hbText(key)?.takeIf { it.length <= 3_000_000 } ?: throw HellobikeException("验证图片无效，请刷新")
            val raw = if (encoded.startsWith("data:image/")) encoded.substringAfter(',') else encoded
            val bytes = try { Base64.getMimeDecoder().decode(raw) } catch (_: IllegalArgumentException) { throw HellobikeException("验证图片无效，请刷新") }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096 || bounds.outWidth.toLong() * bounds.outHeight > 4_000_000)
                throw HellobikeException("验证图片尺寸异常，请刷新")
            return bytes to bounds
        }
        val (background, bounds) = decode("bigImage")
        val (piece, pieceBounds) = decode("smallImage")
        val y = data.hbInt("locationY") ?: throw HellobikeException("验证图片缺少位置信息")
        if (pieceBounds.outWidth >= bounds.outWidth || y < 0 || y + pieceBounds.outHeight > bounds.outHeight)
            throw HellobikeException("验证图片不匹配，请刷新")
        return HellobikeSlider(background, piece, y, bounds.outWidth, bounds.outHeight, pieceBounds.outWidth, pieceBounds.outHeight)
    }
    override suspend fun login(phone: String, code: String, region: HellobikeRegion?, clientId: String, loginGuid: String): HellobikeAccount {
        if (!code.matches(Regex("[0-9]{4,8}"))) throw HellobikeException("请输入短信验证码")
        val data = request("user.account.login", auth(phone, region, clientId).withField("code", code)
            .withField("appChannel", HellobikeProtocol.NativeChannel).withField("ssid", loginSsid)
            .withField("loginGuid", loginGuid).withField("apkSignHash", apkSignHash))
        // LoginCheckImpl.Ea completes login before broadcasting unavailableMobile;
        // neither this field nor needRebindMobile is a login rejection here.
        val token = data.hbText("token")?.takeIf(String::isNotBlank) ?: throw HellobikeException("user.account.login：响应缺少 token")
        val guid = data.hbText("guid")?.takeIf(String::isNotBlank) ?: throw HellobikeException("user.account.login：响应缺少 guid")
        return HellobikeAccount(phone, token, guid, clientId, data.hbText("ticket").orEmpty())
    }
    override suspend fun region(position: HellobikePosition): HellobikeRegion {
        val data = request("hello.lbs.code.regeo", HellobikeProtocol.lbsFields(position, now()))
        val component = data.objectOrNull("data")?.arrayOrNull("reGeoCodesList")?.objectOrNull(0)?.objectOrNull("addressComponent")
            ?: throw HellobikeException("暂时无法确认所在地区，请稍后重新检查车辆")
        val region = HellobikeRegion(component.hbText("cityCode").orEmpty(), component.hbText("adCode").orEmpty(),
            listOf(component.hbText("city")?.takeIf(String::isNotBlank) ?: component.hbText("province").orEmpty(),
                component.hbText("district").orEmpty()).filter(String::isNotBlank).joinToString(" "))
        if (!region.valid) throw HellobikeException("定位未返回有效地区，请稍后重新检查车辆")
        return region
    }
    override suspend fun checkAccount(account: HellobikeAccount) {
        // Only the envelope's official token-expiry codes matter to account status.
        // Ride fields and ordinary business results belong to ride discovery.
        request("user.ride.bikeCheck", HellobikeProtocol.rideCheckFields(account))
    }
    override suspend fun checkRide(account: HellobikeAccount): HellobikeRideReading {
        val data = request("user.ride.bikeCheck", HellobikeProtocol.rideCheckFields(account))
        val info = data.objectOrNull("ride")?.objectOrNull("order") ?: return HellobikeRideReading.NoRide
        val guid = info.hbText("orderGuid")
            ?.takeIf(String::isNotBlank) ?: throw HellobikeException("骑行状态缺少订单标识，请刷新")
        val status = info.hbInt("status") ?: throw HellobikeException("骑行状态缺少订单状态，请刷新")
        return HellobikeRideReading.Ride(guid, info.hbText("bikeNo").orEmpty(), status, userGuid = info.hbText("userGuid"))
    }
    override suspend fun ridingInfo(account: HellobikeAccount): HellobikeRideReading {
        // Official riding page: adaptTwRideCheckToBikeOrder maps originGuid to orderGuid.
        val data = ride("user.tw.ride.check", account, jsonObjectOf("bizType" to 0))
        val info = data.objectOrNull("rideInfo")?.takeIf { it.isNotEmpty() } ?: return HellobikeRideReading.NoRide
        // runBikeRidingInfo tests nonempty rideInfo and FINISH; it does not require
        // originGuid or rideStatus to exist before displaying the returned ride.
        val guid = info.hbText("originGuid").orEmpty()
        val status = info.hbInt("rideStatus")
        return HellobikeRideReading.Ride(guid, data.objectOrNull("bikeInfo")?.hbText("bikeNo").orEmpty(), status,
            userGuid = info.hbText("userGuid"), temporaryLockSupported = data.arrayOrNull("rideFunctionSettings").orEmpty()
                .any { (it as? JsonObject)?.hbInt("type") == 0 })
    }
    private fun location(region: HellobikeRegion, position: HellobikePosition): JsonObject {
        if (!region.valid || !position.fresh(elapsed())) throw HellobikeException("定位数据不完整，请重新获取位置")
        return jsonObjectOf("lat" to position.lat.toString(), "lng" to position.lng.toString(), "cityCode" to region.cityCode, "adCode" to region.adCode)
    }
    private suspend fun ride(action: String, account: HellobikeAccount, fields: JsonObject) =
        request(action, JsonObject(HellobikeProtocol.rideFields(account) + fields))
    override suspend fun inspectBike(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck {
        val page = ride("tw.bike.unlock.page.basic.info", account, JsonObject(location(region, position) +
            jsonObjectOf("bikeNo" to bikeNo, "bikeShowNo" to bikeNo, "businessType" to 0, "pageVersion" to 2,
                "userLat" to position.lat, "userLng" to position.lng)))
        return HellobikePrecheck(bikeNo, "", page.objectOrNull("ridePriceInfo")?.hbText("priceRule").orEmpty(),
            elapsed(), region, position, notice = HellobikeProtocol.message(page))
    }
    override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck {
        val scanId = HellobikeProtocol.scanRequestId(account.userGuid, bikeNo, now())
        val pre = ride("user.ride.pre.ride", account, JsonObject(location(region, position) + jsonObjectOf("bikeNo" to bikeNo, "isHandleTag" to true,
            "scanReqId" to scanId, "scan_req_id" to scanId, "model" to 0, "force" to 1, "platform" to 0,
            "scanEntrance" to 1, "openBluetooth" to 1, "bleStatus" to 0, "locationStatus" to 1, "pageVersion" to 2, "mode" to 0, "bizType" to 0,
            "userConfirmContinueRide" to false)))
        val skip = pre.objectOrNull("skipConfirmResult")
        // Official executor 1 means the server has already issued the unlock in pre-ride.
        val skipConfirmation = skip?.hbBoolean("canSkip") == true
        val serverUnlock = skipConfirmation && skip.hbInt("executor") == 1
        val order = if (serverUnlock) pre.hbText("rideGuid") else pre.hbText("orderGuid")
        // The official server executor starts checking rideGuid directly, before the
        // ordinary confirmation guards. Never lose an already accepted server unlock.
        if (serverUnlock) {
            // autoUnlock forwards scanRes.rideGuid as supplied. It does not discover
            // an originGuid through the riding-page API when this field is absent.
            val rideGuid = order.orEmpty()
            return HellobikePrecheck(pre.hbText("bikeNo") ?: bikeNo, rideGuid, pre.hbText("priceRule").orEmpty(),
                elapsed(), region, position, scanId, notice = HellobikeProtocol.message(pre), serverUnlockStarted = true, skipConfirmation = true)
        }
        val cause = pre.hbInt("causeType")
        // New H5 isBlockedByPreCheck, distinct from the native S() fallback.
        if (pre.hbBoolean("result") == false && cause in setOf(1204, 1235, 1231, 1109, 1115))
            throw HellobikeException(HellobikeProtocol.message(pre, includeEnvelope = true)
                ?: HellobikeProtocol.missingReason("user.ride.pre.ride", pre), rejected = true)
        // Native-app H5 handles these through direct navigation, risk authentication,
        // recommendation/modals or BLE. This app must hand them back to the official
        // flow rather than pretending they were ordinary informational notices.
        val needsOfficialFlow = pre.hbInt("canFaceRecognition")?.let { it != 0 } == true ||
            pre.hbBoolean("canFaceRecognition") == true || cause in setOf(1420, 1421, 1215) ||
            pre.hbInt("popupPromptType") == 3 ||
            !skipConfirmation && (pre.objectOrNull("directGoto") != null ||
                pre.objectOrNull("recommendProperties") != null || pre.arrayOrNull("popups").orEmpty().isNotEmpty() ||
                pre.arrayOrNull("notices").orEmpty().any { (it as? JsonObject)?.hbInt("warnType") in setOf(1429, 1213, 1227, 1237) } ||
                pre.hbBoolean("result") != true && cause in setOf(1130, 1136, 309, 1109, 1119, 1120, 1115, 30012))
        if (needsOfficialFlow) throw HellobikeException(HellobikeProtocol.message(pre, includeEnvelope = true)
            ?: "user.ride.pre.ride：返回了当前应用尚未接入的官方交互，服务端未提供提示", officialRequired = true)
        // wxHandler's ordinary false result opens a blocking notice. Only the
        // official client-skip path goes directly to onConfirmUnlock and its guard.
        if (pre.hbBoolean("result") != true && !skipConfirmation)
            throw HellobikeException(HellobikeProtocol.message(pre, includeEnvelope = true)
                ?: HellobikeProtocol.missingReason("user.ride.pre.ride", pre), rejected = true)
        return HellobikePrecheck(pre.hbText("bikeNo") ?: bikeNo, order.orEmpty(), pre.hbText("priceRule").orEmpty(),
            elapsed(), region, position, scanId, notice = HellobikeProtocol.message(pre), skipConfirmation = skipConfirmation,
            userConfirmContinueRide = pre.hbBoolean("result") == true && pre.hbInt("unpaidEntranceMode") in setOf(2, 3))
    }
    override suspend fun create(account: HellobikeAccount, precheck: HellobikePrecheck, position: HellobikePosition): String {
        if (precheck.serverUnlockStarted) return precheck.orderGuid
        if (precheck.scanReqId.isBlank()) throw HellobikeException("尚未完成扫码检查，请重新检查车辆")
        if (!precheck.fresh(elapsed())) throw HellobikeException("车辆检查数据不完整，请重新检查")
        val data = ride("user.ride.create", account, JsonObject(location(precheck.region, position) + jsonObjectOf("bikeNo" to precheck.bikeNo,
            "model" to 0, "mode" to 0, "force" to 1, "rideLicenseforce" to 1,
            // onConfirmUnlock stores scan_req_id for tracking; parseBikeParams
            // reads the absent scanReqId/orderGuid properties and JSON omits them.
            "platform" to 2, "connectBluetooth" to false, "insurePlanType" to -1,
            "userConfirmContinueRide" to precheck.userConfirmContinueRide)))
        // parseResult adopts rideId when present and starts order.status even when
        // it is absent. A successful envelope is not rejected by a made-up ID rule.
        return data.hbText("rideId").orEmpty()
    }
    override suspend fun orderState(account: HellobikeAccount, rideGuid: String): HellobikeOrderReading {
        val data = ride("user.ride.order.status", account, jsonObjectOf("rideGuid" to rideGuid.takeIf(String::isNotBlank), "bizType" to 1))
        return HellobikeOrderReading(data.hbInt("rideStatus"), data.hbInt("subStatus"),
            HellobikeProtocol.message(data))
    }
    override suspend fun orderEnded(account: HellobikeAccount, rideGuid: String): Boolean = orderState(account, rideGuid).ended
    override suspend fun endInfo(account: HellobikeAccount, rideGuid: String): HellobikeEndInfo {
        val data = ride("tw.end.module.info", account, jsonObjectOf("rideGuid" to rideGuid, "bizType" to 0))

        val trade = data.objectOrNull("tradeRegionInfo")
        val region = data.objectOrNull("rideRegionInfo")
        return HellobikeEndInfo(rideGuid, trade?.hbInt("orderStatus") == 2,
            trade?.objectOrNull("orderInfo")?.hbText("payAmount")?.let(HellobikeProtocol::money),
            region?.hbText("rideDuration")?.toLongOrNull()?.takeIf { it >= 0 }, region?.hbText("rideDistance"))
    }
    override suspend fun preClose(account: HellobikeAccount, session: HellobikeSession, position: HellobikePosition, region: HellobikeRegion,
        poll: Boolean, commit: Boolean): HellobikeCloseReading {
        val operation = if (commit) 2 else 0
        val data = ride("ride.hub.pre.close", account, JsonObject(location(region, position) + jsonObjectOf("orderGuid" to session.rideGuid,
            "bikeNo" to session.bikeNo, "operateType" to operation,
            "firstOperate" to firstReturnOperation(account, session.rideGuid, operation), "poll" to if (poll) 1 else 0,
            "hasIllegalEdu" to false, "locRepTime" to now())))
        return HellobikeProtocol.closeReading(data, commit)
    }
    override suspend fun payDetail(account: HellobikeAccount, session: HellobikeSession): HellobikePayDetail {
        val data = request("user.artemis.pay.page.order.detail", JsonObject(HellobikeProtocol.rideFields(account) +
            jsonObjectOf("cityCode" to session.region?.cityCode, "rideGuid" to session.rideGuid)))
        val info = data.objectOrNull("orderInfo") ?: throw HellobikeException("费用信息尚未生成，请稍后刷新")
        val amount = info.hbText("payAmount")?.let(HellobikeProtocol::money)
        val status = when (info.hbText("payStatus")) { "SUCCESS" -> HellobikeSettlement.Paid; "CLOSED" -> HellobikeSettlement.Closed; else -> HellobikeSettlement.Unknown }
        val base = HellobikePayDetail(session.rideGuid, data.hbText("tradeId").orEmpty(), amount, status)
        if (status != HellobikeSettlement.Unknown) return base
        if (data.hbInt("bizType")?.let { it != 0 } == true) return base.copy(unsupported = HellobikeProtocol.message(data)
            ?: "当前应用尚未接入返回的费用业务类型（bizType=${data.hbInt("bizType")}）")
        val list = info.arrayOrNull("unpaidPayList") ?: return base.copy(unsupported = "费用信息不完整，请在哈啰核对")
        val unpaid = list.filter { (it as? JsonObject)?.hbText("payStatus") != "SUCCESS" }
        if (unpaid.isEmpty()) return base
        val orders = unpaid.map { item ->
            val entry = item as? JsonObject ?: throw HellobikeException("费用项目格式异常，请在哈啰核对", officialRequired = true)
            val guid = entry.hbText("payNo") ?: throw HellobikeException("费用缺少付款标识，请在哈啰核对", officialRequired = true)
            val price = entry.hbText("payPrice")?.let(HellobikeProtocol::money) ?: throw HellobikeException("付款金额异常，请在哈啰核对", officialRequired = true)
            // Ordinary H5 payList uses payNo. tradeId -> orderId belongs to its separate merged-payment helper.
            HellobikePayOrder(guid, price, entry.hbText("orderId").orEmpty())
        }
        return base.copy(settlement = HellobikeSettlement.Pending, orders = orders)
    }
    override suspend fun payChannels(account: HellobikeAccount, session: HellobikeSession, detail: HellobikePayDetail): List<HellobikePayOption> {
        if (detail.settlement != HellobikeSettlement.Pending || detail.orders.isEmpty()) return emptyList()
        val single = detail.orders.singleOrNull()
        val data = request("com.alphapay.getPayChannel", JsonObject(HellobikeProtocol.paymentFields(account) + jsonObjectOf(
            "businessType" to "1", "scene" to "407", "adCode" to session.region?.adCode, "cityCode" to session.region?.cityCode,
            "amount" to detail.amount, "waitPayGuid" to single?.guid, "orderId" to single?.orderId?.takeIf(String::isNotBlank),
            "voucherNos" to if (single == null) jsonArrayOf(detail.orders.map { it.guid }) else null,
            "actionOrigin" to "StandardCashier", "tenantId" to "t_cn_hb")))
        return data.arrayOrNull("channelList")?.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val code = entry.hbText("channelCode")?.takeIf { it in HellobikeSupportedChannels } ?: return@mapNotNull null
            val name = when (code) { "001" -> "支付宝"; "003" -> "哈啰余额"; else -> "银联" }
            val amount = entry.hbText("realAmount")?.let(HellobikeProtocol::money) ?: detail.amount ?: return@mapNotNull null
            // PayTypeBeanExtKt.a only selects a signing channel when one was returned.
            // BalanceGuideAbility.d/e apply to 013, not to ordinary 001/003/1201.
            val needsOpen = entry.hbBoolean("supportAgreement") == true && entry.hbBoolean("signState") != true &&
                !entry.hbText("agreementChannelCode").isNullOrBlank() &&
                (entry.hbInt("agreementButtonModel") == 1 &&
                    (entry.objectOrNull("agreementActionInfo")?.hbBoolean("showAgreementTag") != true || entry.hbBoolean("switchStates") == true) ||
                    entry.hbInt("agreementButtonModel") == 2 && entry.objectOrNull("agreementActionInfo")?.hbBoolean("payGuideSign") == true)
            // PayTypeBean.isBalanceEnough falls back to accountBalance when the flag is absent.
            val balanceEnough = entry.hbBoolean("balanceEnough")
                ?: (entry.hbText("accountBalance") ?: "0").toBigDecimalOrNull()?.let { it >= amount.toBigDecimal() }
                ?: false
            val reason = when {
                needsOpen -> HellobikeProtocol.message(entry) ?: "当前应用尚未接入此支付渠道的签约流程"
                entry.hbBoolean("enableSelect") == false -> HellobikeProtocol.message(entry)
                    ?: "com.alphapay.getPayChannel：enableSelect=false，服务端未提供原因"
                code == "003" && !balanceEnough -> entry.hbText("balanceText")?.takeIf(String::isNotBlank)
                    ?: HellobikeProtocol.message(entry) ?: "com.alphapay.getPayChannel：余额检查未通过，服务端未提供原因"
                detail.orders.size != 1 && amount != detail.amount -> HellobikeProtocol.message(entry)
                    ?: "支付渠道返回的组合金额与本笔费用合计不一致"
                else -> null
            }
            HellobikePayOption(LifePayChannel(code, name, reason == null, needsOpen, reason), amount,
                entry.hbText("marketingActivityId"), entry.hbText("marketingType"), entry.hbText("channelOperationInfoExt"),
                entry.objectOrNull("extension") ?: JsonObject(emptyMap()))
        }?.distinctBy { it.channel.method }.orEmpty()
    }
    override suspend fun pay(account: HellobikeAccount, session: HellobikeSession, orders: List<HellobikePayOrder>, option: HellobikePayOption): HellobikePayComponent {
        if (option.channel.method !in HellobikeSupportedChannels || option.channel.available != true || option.channel.needsOpen == true ||
            orders.isEmpty() || orders.sumOf { it.amount.toBigDecimal() } != option.amount.toBigDecimal()) throw HellobikeException("付款条件已变化，请刷新费用")
        val data = request("com.alphapay.paymentOrder.pay", JsonObject(HellobikeProtocol.paymentFields(account) + jsonObjectOf(
            "amount" to option.amount, "channel" to option.channel.method, "businessType" to "1",
            "orders" to jsonArrayOf(orders.map { jsonObjectOf("guid" to it.guid, "amount" to it.amount, "type" to "1",
                "businessType" to it.businessType, "orderId" to it.orderId.takeIf(String::isNotBlank)) }).toString(),
            "adCode" to session.region?.adCode, "cityCode" to session.region?.cityCode, "actionOrigin" to "StandardCashier",
            "marketingActivityId" to option.marketingActivityId, "marketingType" to option.marketingType,
            "channelOperationInfoExt" to option.channelOperationInfoExt, "extension" to option.extension)))
        val returnedOrders = data.arrayOrNull("orders")?.map { item ->
            val entry = item as? JsonObject ?: throw HellobikeException("付款订单返回异常，请查询结果")
            val guid = entry.hbText("guid") ?: throw HellobikeException("付款订单缺少标识，请查询结果")
            val original = orders.singleOrNull { it.guid == guid } ?: throw HellobikeException("付款订单不一致，请在哈啰核对", officialRequired = true)
            val amount = entry.hbText("amount")?.let(HellobikeProtocol::money) ?: original.amount
            val business = entry.hbText("businessType") ?: original.businessType
            if (amount != original.amount || business != original.businessType) throw HellobikeException("付款金额或业务不一致，请在哈啰核对", officialRequired = true)
            val id = entry.hbText("orderId") ?: original.orderId
            if (original.orderId.isNotBlank() && id != original.orderId) throw HellobikeException("付款订单不一致，请在哈啰核对", officialRequired = true)
            original.copy(orderId = id)
        } ?: orders
        val channel = data.hbText("channel") ?: throw HellobikeException("付款渠道尚未确认，请查询结果")
        val payload = data.objectOrNull("credential")?.hbText("params")?.takeIf { it.length <= 262_144 }
        return HellobikePayComponent(channel, data.hbText("status").orEmpty(), returnedOrders,
            data.objectOrNull("extension") ?: option.extension, payload)
    }
    override suspend fun paymentPaid(account: HellobikeAccount, order: HellobikePayOrder, extension: JsonObject): Boolean {
        val data = request("com.alphapay.facade.orderSingleQuery", JsonObject(HellobikeProtocol.paymentFields(account) + jsonObjectOf(
            "guid" to order.guid, "orderId" to order.orderId.takeIf(String::isNotBlank), "businessType" to order.businessType, "extension" to extension)))
        if (data.hbText("guid") != order.guid) throw HellobikeException("付款查询缺少匹配的订单标识，请在哈啰核对", officialRequired = true)
        if (order.orderId.isNotBlank() && data.hbText("businessId")?.let { it != order.orderId } == true)
            throw HellobikeException("付款查询订单不一致，请在哈啰核对", officialRequired = true)
        data.hbText("amount")?.let { if (HellobikeProtocol.money(it) != order.amount) throw HellobikeException("付款查询金额不一致，请在哈啰核对", officialRequired = true) }
        val status = data["payStatus"] as? JsonPrimitive
        // QueryOrderData exposes an int. The consumer checks its parsed value,
        // without imposing another rule on the original JSON token's representation.
        return status?.intOrNull == 1
    }
}

internal fun JsonObject.hbText(key: String): String? = (get(key) as? JsonPrimitive)?.takeUnless { it == JsonNull }
    ?.content?.takeIf(String::isNotBlank)
internal fun JsonObject.hbInt(key: String): Int? = (get(key) as? JsonPrimitive)?.content?.takeIf { it.matches(Regex("-?[0-9]+")) }?.toIntOrNull()
internal fun JsonObject.hbBoolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
