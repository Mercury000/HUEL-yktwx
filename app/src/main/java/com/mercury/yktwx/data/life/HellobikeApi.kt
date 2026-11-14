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
    const val NativePaymentSdkVersion = "1.55.0"
    const val NativeVersion = "7.0.75"
    // Walle signing-block channel from the supplied, SHA-256 identified official APK.
    const val NativeChannel = "a0010005"
    // Official AppHelloMiniBrand HTML and scripts fetched on 2026-11-13.
    const val RideH5Version = "7.0.70"
    private val rideActions = setOf("user.tw.order.check", "user.ride.order.status",
        "tw.bike.unlock.page.basic.info", "user.ride.pre.ride", "user.ride.create", "ride.hub.pre.close")
    private val payActions = setOf("com.alphapay.getPayChannel", "com.alphapay.paymentOrder.pay", "com.alphapay.facade.orderSingleQuery")
    fun endpoint(action: String): String = when {
        action == "user.account.login" -> "https://api.hellobike.com/auth"
        action in setOf("user.account.sendCodeV3", "user.account.slideCaptcha") -> "https://api.hellobike.com/api"
        action == "hello.lbs.code.regeo" -> "https://hello-location-based-services.hellobike.com/api?$action"
        action == "user.artemis.pay.page.order.detail" -> "https://bike.hellobike.com/api"
        action == "user.ride.bikeCheck" -> "https://bike.hellobike.com/api"
        action in setOf("student.audit.info", "student.card.get.v3", "tw.standard.preOrder", "tw.ridecard.user.list", "tw.ridecard.commodity") -> "https://bike.hellobike.com/api"
        action in payActions -> "https://api.hellobike.com/api?$action"
        action in rideActions -> {
            val host = if (action.startsWith("user.tw.") || action == "user.ride.order.status") "ebike" else "bike"
            val path = when (action) {
                "user.ride.pre.ride", "tw.bike.unlock.page.basic.info" -> "/api/$action"
                "user.ride.create", "ride.hub.pre.close" -> "/api?$action"
                else -> "/api"
            }
            "https://$host.hellobike.com$path"
        }
        else -> throw HellobikeException("不支持的哈啰业务")
    }
    fun rideFields(account: HellobikeAccount) = jsonObjectOf("token" to account.token,
        "ticket" to account.ticket.takeIf(String::isNotBlank), "systemCode" to account.systemCode,
        "version" to RideH5Version, "h5Version" to RideH5Version, "appName" to "AppHelloMiniBrand",
        "from" to "h5", "sourceMarkup" to "ohoSaaS")
    // BikeRideCheckApiModel extends native BaseApiModel; it has no H5 or bizType fields.
    fun rideCheckFields(account: HellobikeAccount) = jsonObjectOf("token" to account.token,
        "ticket" to account.ticket.takeIf(String::isNotBlank), "systemCode" to account.systemCode,
        "version" to NativeVersion, "clientId" to account.clientId)
    fun paymentFields(account: HellobikeAccount) = jsonObjectOf("token" to account.token,
        "ticket" to account.ticket.takeIf(String::isNotBlank), "systemCode" to account.systemCode,
        "from" to "h5", "currentSdkVersion" to PaymentSdkVersion)
    fun cardFields(account: HellobikeAccount, myCards: Boolean = false, studentAudit: Boolean = false) = jsonObjectOf(
        "token" to account.token, "ticket" to account.ticket.takeIf(String::isNotBlank), "from" to "h5",
        "version" to if (myCards) "7.0.75" else "7.0.65", "systemCode" to if (studentAudit) JsonPrimitive(63) else JsonPrimitive("62"),
        "osSystemCode" to if (myCards) null else 62, "platform" to if (myCards) JsonPrimitive(4) else JsonPrimitive("0"),
        "os" to if (myCards) "Android" else null)
    fun nativePaymentFields(account: HellobikeAccount) = jsonObjectOf("token" to account.token,
        "ticket" to account.ticket.takeIf(String::isNotBlank), "systemCode" to account.systemCode, "version" to NativeVersion,
        "clientId" to account.clientId, "currentSdkOS" to "Android", "currentSdkVersion" to NativePaymentSdkVersion)
    fun lbsFields(position: HellobikePosition, timestamp: Long, signingKey: String = LbsSigningKey): JsonObject {
        val arg0 = jsonObjectOf("locations" to jsonArrayOf(listOf(jsonObjectOf("lat" to position.lat, "lng" to position.lng))), "extensions" to "all")
        val signature = MessageDigest.getInstance("MD5").digest((arg0.toString() + timestamp + signingKey).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return jsonObjectOf("appName" to "AppHelloBikeMainH5", "version" to "7.0.30", "releaseVersion" to "7.0.30",
            "from" to "h5", "systemCode" to "62", "arg0" to arg0.toString(), "method" to "reGeoCodingSearch",
            "arg1" to jsonObjectOf("appKey" to LbsAppKey, "signature" to signature, "src" to "SMALL_PROGRAM", "timestamp" to timestamp))
    }
    fun normaliseRideGuid(value: String) = if (value.startsWith("TW")) value.substring(2) else value
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
    fun money(value: String): String? = value.takeIf { it.matches(Regex("[0-9]{1,9}(\\.[0-9]{1,2})?")) }
        ?.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO }?.setScale(2)?.toPlainString()
    fun closeReading(data: JsonObject, commit: Boolean = false): HellobikeCloseReading {
        val cause = data.hbInt("causeType") ?: throw HellobikeException("还车结果尚未确认，请刷新订单")
        val cloudMessage = message(data)
        // This endpoint is P10. Its H5 consumer branches on causeType, independently of state/status.
        val processing = cause in setOf(1101, 1108)
        if (cause == 1100) return HellobikeCloseReading(false, false, cloudMessage, completed = true)
        if (commit && (processing || cause in setOf(1102, 1103))) return HellobikeCloseReading(processing, false, cloudMessage)
        if (processing) return HellobikeCloseReading(true, false, cloudMessage)
        // The type selects a transport. Accompanying keys/penalty text do not themselves
        // reject the server's standard P10 return result.
        val unsupported = data.hbInt("type")?.let { it != 1 } == true
        if (unsupported) throw HellobikeException(cloudMessage ?: missingReason("ride.hub.pre.close", data), officialRequired = true)
        if (cause in setOf(1102, 1103))
            return HellobikeCloseReading(false, true, cloudMessage)
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
    private val alipayInstalled: () -> Boolean = { false },
    private val clientIp: () -> String = { "" },
) : HellobikeService {
    private val loginSsid by lazy { ssid() }
    private suspend fun request(action: String, fields: JsonObject): JsonObject {
        val body = fields.withField("action", action)
        val nativePayment = action.startsWith("com.alphapay.") && fields.hbText("currentSdkOS") == "Android"
        val plain = action.startsWith("com.alphapay.") && !nativePayment || action == "hello.lbs.code.regeo"
        val endpoint = HellobikeProtocol.endpoint(action).let { if (nativePayment) it.substringBefore('?') else it }
        val req = AppHttp.request(endpoint).header("inner_action", action)
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
            val data = envelope.objectOrNull("data") ?: throw HellobikeException("$action：响应缺少 data")
            val responseMessage = envelope.hbText("msg") ?: envelope.hbText("resultMessage")
            if (responseMessage != null) data.withField("_officialResponseMessage", responseMessage) else data
        }
    }
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
    override suspend fun checkRide(account: HellobikeAccount): HellobikeRideReading {
        val data = request("user.ride.bikeCheck", HellobikeProtocol.rideCheckFields(account))
        val info = data.objectOrNull("ride")?.objectOrNull("order") ?: return HellobikeRideReading.NoRide
        val guid = info.hbText("orderGuid")?.let(HellobikeProtocol::normaliseRideGuid)
            ?.takeIf(String::isNotBlank) ?: throw HellobikeException("骑行状态缺少订单标识，请刷新")
        val status = info.hbInt("status") ?: throw HellobikeException("骑行状态缺少订单状态，请刷新")
        return HellobikeRideReading.Ride(guid, info.hbText("bikeNo").orEmpty(), status, userGuid = info.hbText("userGuid"))
    }
    private fun location(region: HellobikeRegion, position: HellobikePosition): JsonObject {
        if (!region.valid || !position.fresh(elapsed())) throw HellobikeException("定位数据不完整，请重新获取位置")
        return jsonObjectOf("lat" to position.lat.toString(), "lng" to position.lng.toString(), "cityCode" to region.cityCode, "adCode" to region.adCode)
    }
    private suspend fun ride(action: String, account: HellobikeAccount, fields: JsonObject) =
        request(action, JsonObject(HellobikeProtocol.rideFields(account) + fields))
    override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck {
        val scanId = HellobikeProtocol.scanRequestId(account.userGuid, bikeNo, now())
        val pre = ride("user.ride.pre.ride", account, JsonObject(location(region, position) + jsonObjectOf("bikeNo" to bikeNo, "isHandleTag" to true,
            "scanReqId" to scanId, "scan_req_id" to scanId, "model" to 0, "force" to 1, "platform" to 0,
            "scanEntrance" to 1, "openBluetooth" to 0, "bleStatus" to false, "locationStatus" to 1, "pageVersion" to 2, "mode" to 0, "bizType" to 0)))
        val order = pre.hbText("orderGuid")
        // S/z0/R dispatch these responses to separate official dialogs or authentication.
        // Preserve that requirement instead of bypassing it on result=true.
        val needsOfficialFlow = pre.hbInt("causeType") in setOf(1421, 1215, 1130, 1136, 1133, 1225) ||
            pre.hbInt("causeType") == 1226 && pre.objectOrNull("nearBikeInfo") != null ||
            pre.hbBoolean("netBluetoothModel") == true && pre.hbInt("popupPromptType") == 3 ||
            pre.hbInt("canFaceRecognition") == 1 && pre.hbText("launchBioAuthRequestResponseDto") != null ||
            (pre.objectOrNull("recommendProperties") != null || pre.hbInt("causeType") == 1427) && pre.objectOrNull("popup") != null ||
            pre.arrayOrNull("notices").orEmpty().any {
                val warn = (it as? JsonObject)?.hbInt("warnType")
                warn in setOf(1225, 1234) || warn == 1226 && pre.objectOrNull("nearBikeInfo") != null
            }
        if (needsOfficialFlow) throw HellobikeException(HellobikeProtocol.message(pre, includeEnvelope = true)
            ?: "user.ride.pre.ride：返回了当前应用尚未接入的官方交互，服务端未提供提示", officialRequired = true)
        // BikePreRideCreateAction.S: result == true OR causeType == 1100.
        // Neither missBike nor an absent bikeType is an additional rejection here.
        if (pre.hbBoolean("result") != true && pre.hbInt("causeType") != 1100)
            throw HellobikeException(HellobikeProtocol.message(pre, includeEnvelope = true)
                ?: HellobikeProtocol.missingReason("user.ride.pre.ride", pre), rejected = true)
        if (order.isNullOrBlank()) throw HellobikeException("user.ride.pre.ride：响应缺少 orderGuid")
        val page = ride("tw.bike.unlock.page.basic.info", account, JsonObject(location(region, position) +
            jsonObjectOf("bikeNo" to bikeNo, "bikeShowNo" to bikeNo, "businessType" to 0, "pageVersion" to 2)))
        val rule = page.objectOrNull("ridePriceInfo")?.hbText("priceRule").orEmpty()
        return HellobikePrecheck(bikeNo, HellobikeProtocol.normaliseRideGuid(order), rule, elapsed(), region, position, scanId,
            notice = HellobikeProtocol.message(pre))
    }
    override suspend fun create(account: HellobikeAccount, precheck: HellobikePrecheck, position: HellobikePosition) {
        if (!precheck.fresh(elapsed())) throw HellobikeException("车辆检查数据不完整，请重新检查")
        val data = ride("user.ride.create", account, JsonObject(location(precheck.region, position) + jsonObjectOf("bikeNo" to precheck.bikeNo,
            "orderGuid" to precheck.orderGuid, "model" to 0, "mode" to 0, "force" to 1, "rideLicenseforce" to 1,
            "scanReqId" to precheck.scanReqId.takeIf(String::isNotBlank), "platform" to 2,
            "connectBluetooth" to false, "insurePlanType" to -1, "userConfirmContinueRide" to false)))
        val returned = data.hbText("rideId")?.let(HellobikeProtocol::normaliseRideGuid)
        if (returned != null && returned != precheck.orderGuid)
            throw HellobikeException("返回订单与本次开锁不一致，请在哈啰核对", officialRequired = true)
        if (data.hbBoolean("result") != true || returned == null)
            throw HellobikeException(HellobikeProtocol.message(data, includeEnvelope = true) ?: HellobikeProtocol.missingReason("user.ride.create", data),
                rejected = data.hbBoolean("result") == false)
    }
    override suspend fun orderState(account: HellobikeAccount, rideGuid: String): HellobikeOrderReading {
        val data = ride("user.ride.order.status", account, jsonObjectOf("rideGuid" to rideGuid, "bizType" to 1))
        data.checkRideIdentity(rideGuid)
        return HellobikeOrderReading(data.hbInt("rideStatus"), data.hbInt("subStatus"),
            HellobikeProtocol.message(data))
    }
    override suspend fun orderEnded(account: HellobikeAccount, rideGuid: String): Boolean = orderState(account, rideGuid).ended
    override suspend fun preClose(account: HellobikeAccount, session: HellobikeSession, position: HellobikePosition, region: HellobikeRegion,
        poll: Boolean, commit: Boolean): HellobikeCloseReading {
        val data = ride("ride.hub.pre.close", account, JsonObject(location(region, position) + jsonObjectOf("orderGuid" to session.rideGuid,
            "bikeNo" to session.bikeNo, "operateType" to if (commit) 2 else 0, "poll" to if (poll) 1 else 0,
            "hasIllegalEdu" to false, "locRepTime" to now())))
        data.checkRideIdentity(session.rideGuid)
        return HellobikeProtocol.closeReading(data, commit)
    }
    override suspend fun payDetail(account: HellobikeAccount, session: HellobikeSession): HellobikePayDetail {
        val data = request("user.artemis.pay.page.order.detail", JsonObject(HellobikeProtocol.rideFields(account) +
            jsonObjectOf("cityCode" to session.region?.cityCode, "rideGuid" to session.rideGuid)))
        val returned = data.hbText("rideGuid")?.let(HellobikeProtocol::normaliseRideGuid)
        if (returned != session.rideGuid) throw HellobikeException("费用订单与本次骑行不一致，请在哈啰核对", officialRequired = true)
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
        val total = orders.sumOf { it.amount.toBigDecimal() }
        if (orders.map { it.guid }.distinct().size != orders.size || amount == null || total != amount.toBigDecimal())
            return base.copy(unsupported = HellobikeProtocol.message(data) ?: "费用响应中的待付合计或付款标识不一致")
        return base.copy(settlement = HellobikeSettlement.Pending, orders = orders)
    }
    override suspend fun payChannels(account: HellobikeAccount, session: HellobikeSession, detail: HellobikePayDetail): List<HellobikePayOption> {
        if (detail.settlement != HellobikeSettlement.Pending || detail.orders.isEmpty()) return emptyList()
        return standardPayChannels(account, session.region, detail.orders, detail.amount, "1", "407")
    }
    private suspend fun standardPayChannels(account: HellobikeAccount, region: HellobikeRegion?, orders: List<HellobikePayOrder>,
        totalAmount: String?, businessType: String, scene: String, native: Boolean = false): List<HellobikePayOption> {
        val single = orders.singleOrNull()
        val fields = if (native) HellobikeProtocol.nativePaymentFields(account) else HellobikeProtocol.paymentFields(account)
        val data = request("com.alphapay.getPayChannel", JsonObject(fields + jsonObjectOf(
            "businessType" to businessType, "scene" to scene, "adCode" to region?.adCode, "cityCode" to region?.cityCode,
            "amount" to if (native) "" else totalAmount, "waitPayGuid" to single?.guid, "orderId" to single?.orderId?.takeIf(String::isNotBlank),
            "alipayInstallFlag" to if (native) (if (alipayInstalled()) "1" else "0") else null,
            "clientStyleType" to if (native) "" else null, "locale" to if (native) "zh-CN" else null,
            "voucherNos" to if (single == null) jsonArrayOf(orders.map { it.guid }) else null,
            "actionOrigin" to "StandardCashier", "tenantId" to "t_cn_hb")))
        fun leafChannels(item: JsonElement, depth: Int = 0): List<JsonElement> {
            val entry = item as? JsonObject ?: return emptyList()
            val children = entry.arrayOrNull("subChannelList")
            return if (children.isNullOrEmpty()) listOf(entry)
            else if (depth < 4) children.flatMap { leafChannels(it, depth + 1) } else emptyList()
        }
        val options = data.arrayOrNull("channelList")?.flatMap { leafChannels(it) }?.mapNotNull { item ->
            val entry = item as? JsonObject ?: return@mapNotNull null
            val code = entry.hbText("channelCode")?.takeIf { it in HellobikeSupportedChannels } ?: return@mapNotNull null
            val name = when (code) { "001" -> "支付宝"; "003" -> "哈啰余额"; else -> "银联" }
            val amount = entry.hbText("realAmount")?.let(HellobikeProtocol::money) ?: totalAmount ?: return@mapNotNull null
            // Signing is an optional route for these ordinary channels. B0 manual selection
            // does not require an agreement; our ordinary route leaves signing unchecked.
            // PayTypeBean.isBalanceEnough falls back to accountBalance when the flag is absent.
            val balanceEnough = entry.hbBoolean("balanceEnough")
                ?: (entry.hbText("accountBalance") ?: "0").toBigDecimalOrNull()?.let { it >= amount.toBigDecimal() }
                ?: false
            val reason = when {
                code == "003" && !balanceEnough -> entry.hbText("balanceText")?.takeIf(String::isNotBlank)
                    ?: HellobikeProtocol.message(entry) ?: "哈啰余额不足"
                orders.size != 1 && amount != totalAmount -> HellobikeProtocol.message(entry)
                    ?: "支付渠道返回的组合金额与本笔费用合计不一致"
                else -> null
            }
            HellobikePayOption(LifePayChannel(code, name, reason == null, false, reason), amount,
                entry.hbText("marketingActivityId"), entry.hbText("marketingType"), entry.hbText("channelOperationInfoExt"),
                entry.objectOrNull("extension") ?: JsonObject(emptyMap()),
                JsonObject(listOf("promoType", "payProjectGuid", "frontPageId", "hasBundleSaleInfo", "creditModel",
                    "alipayOperationInfo", "preConsultId", "channelComposeInfo", "composeInfo", "businessLineCode")
                    .mapNotNull { key -> (entry[key] ?: data[key])?.takeUnless { it == JsonNull }?.let { key to it } }.toMap()),
                defaultSelected = if (native) entry.hbBoolean("enableSelect") == true else entry.hbBoolean("enableSelect") != false)
        }?.distinctBy { it.channel.method }.orEmpty()
        if (!native) return options
        // PayChannelDataTransfer.f/m/p: explicit default, enableSelect preference,
        // then d() falls back to the first channel that passes the balance check.
        val defaultSetting = data.objectOrNull("extraInfos")?.get("defaultChannelSetting")
        val defaultChannel = (defaultSetting as? JsonObject
            ?: (defaultSetting as? JsonPrimitive)?.contentOrNull?.let { text ->
                runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            })?.hbText("defaultChannel")
        val available = options.filter { it.channel.available == true }
        val initial = available.firstOrNull { it.channel.method == defaultChannel }
            ?: available.firstOrNull { it.defaultSelected } ?: available.firstOrNull()
        return options.map { it.copy(defaultSelected = it === initial) }
    }
    override suspend fun pay(account: HellobikeAccount, session: HellobikeSession, orders: List<HellobikePayOrder>, option: HellobikePayOption): HellobikePayComponent {
        return standardPay(account, session.region, orders, option, "1")
    }
    private suspend fun standardPay(account: HellobikeAccount, region: HellobikeRegion?, orders: List<HellobikePayOrder>,
        option: HellobikePayOption, businessType: String, native: Boolean = false,
        signingFields: JsonObject = JsonObject(emptyMap())): HellobikePayComponent {
        val signing = native && signingFields.isNotEmpty() && option.channel.method == "102"
        if ((!signing && option.channel.method !in HellobikeSupportedChannels) || option.channel.available != true || option.channel.needsOpen == true ||
            orders.isEmpty() || !native && orders.sumOf { it.amount.toBigDecimal() } != option.amount.toBigDecimal()) throw HellobikeException("付款条件已变化，请刷新费用")
        val fields = if (native) HellobikeProtocol.nativePaymentFields(account) else HellobikeProtocol.paymentFields(account)
        val data = request("com.alphapay.paymentOrder.pay", JsonObject(fields + (if (native) option.nativeFields else JsonObject(emptyMap())) + jsonObjectOf(
            "amount" to if (native) JsonPrimitive(orders.sumOf { it.amount.toBigDecimal() }) else JsonPrimitive(option.amount),
            "channel" to option.channel.method, "businessType" to businessType,
            "combinedWith" to if (native) "[]" else null, "clientIP" to if (native) clientIp() else null,
            "locale" to if (native) "zh-CN" else null,
            "orders" to jsonArrayOf(orders.map { jsonObjectOf("guid" to it.guid,
                "amount" to if (native) JsonPrimitive(it.amount.toBigDecimal()) else JsonPrimitive(it.amount), "type" to "1",
                "businessType" to it.businessType, "orderId" to it.orderId.takeIf(String::isNotBlank)) }).toString(),
            "adCode" to region?.adCode, "cityCode" to region?.cityCode, "actionOrigin" to "StandardCashier",
            "marketingActivityId" to option.marketingActivityId, "marketingType" to option.marketingType,
            "channelOperationInfoExt" to option.channelOperationInfoExt, "extension" to option.extension) + signingFields))
        val returnedOrders = data.arrayOrNull("orders")?.map { item ->
            val entry = item as? JsonObject ?: throw HellobikeException("付款订单返回异常，请查询结果")
            val guid = entry.hbText("guid") ?: throw HellobikeException("付款订单缺少标识，请查询结果")
            val original = orders.singleOrNull { it.guid == guid } ?: throw HellobikeException("付款订单不一致，请在哈啰核对", officialRequired = true)
            val amount = entry.hbText("amount")?.let(HellobikeProtocol::money) ?: original.amount
            val business = entry.hbText("businessType") ?: original.businessType
            if (amount != original.amount && (!native || amount != option.amount) || business != original.businessType)
                throw HellobikeException("付款金额或业务不一致，请在哈啰核对", officialRequired = true)
            val id = entry.hbText("orderId") ?: original.orderId
            if (original.orderId.isNotBlank() && id != original.orderId) throw HellobikeException("付款订单不一致，请在哈啰核对", officialRequired = true)
            original.copy(orderId = id)
        } ?: orders
        if (returnedOrders.map { it.guid }.toSet() != orders.map { it.guid }.toSet() || returnedOrders.size != orders.size)
            throw HellobikeException("付款订单返回不完整，请查询结果")
        val channel = data.hbText("channel") ?: throw HellobikeException("付款渠道尚未确认，请查询结果")
        val payload = data.objectOrNull("credential")?.hbText("params")?.takeIf { it.length <= 262_144 }
        return HellobikePayComponent(channel, data.hbText("status").orEmpty(), returnedOrders,
            data.objectOrNull("extension") ?: option.extension, payload)
    }
    override suspend fun studentAudit(account: HellobikeAccount): HellobikeStudentAudit {
        val data = request("student.audit.info", HellobikeProtocol.cardFields(account, studentAudit = true).withField("userNewId", 0))
        return HellobikeStudentAudit(data.hbInt("authState"), data.hbInt("userType"))
    }
    private fun cardLocation(region: HellobikeRegion?, position: HellobikePosition?) = jsonObjectOf(
        "cityCode" to region?.cityCode.orEmpty(), "adCode" to region?.adCode.orEmpty(),
        "lat" to position?.lat?.toString().orEmpty(), "lng" to position?.lng?.toString().orEmpty())
    override suspend fun userCards(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?,
        businessType: Int, page: Int): HellobikeUserCardPage {
        require(businessType in 1..2 && page >= 0)
        val data = request("tw.ridecard.user.list", JsonObject(HellobikeProtocol.cardFields(account, myCards = true) +
            cardLocation(region, position) + jsonObjectOf("businessType" to businessType, "pageStart" to page, "pageSize" to 2, "originTag" to 1)))
        val cards = data.arrayOrNull("rideCards").orEmpty().map { value ->
            val card = value as? JsonObject ?: throw HellobikeException("我的卡列表格式异常，请重试")
            HellobikeUserCard(card.hbText("cardGuid") ?: throw HellobikeException("我的卡响应缺少 cardGuid"),
                card.hbInt("businessType") ?: businessType, card.hbText("name").orEmpty(), card.hbInt("remainDay"),
                card.hbInt("status"), card.hbText("expireSecond")?.toLongOrNull(), card.hbText("desc").orEmpty(),
                renewable = card.hbInt("status") == 0 && card.hbInt("canOperateType") != 0 &&
                    data.hbBoolean(if (businessType == 1) "hasBikePackage" else "hasEvBikePackage") == true)
        }
        return HellobikeUserCardPage(cards, data.hbBoolean("hasNext") == true)
    }
    override suspend fun campusProducts(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?): HellobikeCampusProducts {
        val data = request("student.card.get.v3", JsonObject(HellobikeProtocol.cardFields(account) + cardLocation(region, position) +
            jsonObjectOf("needOptPromo" to true)))
        val products = data.arrayOrNull("cardInfoList").orEmpty().flatMapIndexed { groupIndex, groupValue ->
            val group = groupValue as? JsonObject ?: throw HellobikeException("校园卡商品列表格式异常，请重试")
            group.arrayOrNull("packages").orEmpty().mapIndexed { index, value ->
                val item = value as? JsonObject ?: throw HellobikeException("校园卡商品格式异常，请重试")
                val price = (item.objectOrNull("actualPrice")?.hbText("finalPrice") ?: item.hbText("purchasePrice"))
                    ?.let(HellobikeProtocol::money) ?: throw HellobikeException("校园卡商品响应缺少有效价格")
                val words = item.arrayOrNull("marketingWords").orEmpty().mapNotNull { (it as? JsonObject)?.hbText("wordsValue") }
                HellobikeCampusProduct("$groupIndex:$index", item.hbText("showName").orEmpty(), price,
                    (listOfNotNull(item.hbText("commodityDesc")) + words).distinct().joinToString("\n"),
                    item, group.hbText("tabTitle") ?: group.hbText("headTitle").orEmpty())
            }
        }
        return HellobikeCampusProducts(data.hbText("moduleTitle") ?: "校园骑行卡", products)
    }
    override suspend fun preOrderCard(account: HellobikeAccount, product: HellobikeCampusProduct, region: HellobikeRegion?,
        position: HellobikePosition?): HellobikeCardPurchase {
        if (!product.purchasable) throw HellobikeException(product.unavailableReason ?: "商品信息不完整，请刷新商品")
        val item = product.raw
        val actual = item.objectOrNull("actualPrice") ?: throw HellobikeException("商品价格信息不完整，请刷新商品")
        val packageGuid = item.hbText("packageGuid") ?: throw HellobikeException("商品标识不完整，请刷新商品")
        val biz = jsonObjectOf("channelCode" to item.hbInt("channelCode"), "businessType" to item.hbInt("businessType"),
            "totalPrice" to actual.hbText("finalPrice"), "packageList" to jsonArrayOf(listOf(jsonObjectOf(
                "category" to 1, "packageGuid" to packageGuid, "actualPrice" to actual,
                "pendingInfos" to item.arrayOrNull("pendingInfos"), "sendAccount" to "",
                "payChannelDiscountInfo" to if (product.campus) JsonObject(emptyMap()) else null))))
        if (item.hbInt("businessType") !in 1..2 || item.hbInt("channelCode") == null)
            throw HellobikeException("商品业务信息不完整，请刷新商品")
        val data = request("tw.standard.preOrder", JsonObject(HellobikeProtocol.cardFields(account) +
            (if (product.campus) JsonObject(emptyMap()) else jsonObjectOf("fromTwoWheel" to true)) +
            jsonObjectOf("bizContent" to biz, "packagePlatform" to "0", "adCode" to region?.adCode.orEmpty(), "cityCode" to region?.cityCode.orEmpty(),
                "location" to jsonObjectOf("lat" to position?.lat, "lng" to position?.lng))))
        val business = data.hbText("businessType") ?: throw HellobikeException("预下单响应缺少 businessType，请在哈啰核对订单")
        val scene = data.hbText("businessScene") ?: throw HellobikeException("预下单响应缺少 businessScene，请在哈啰核对订单")
        val amount = data.hbText("totalAmount")?.let(HellobikeProtocol::money) ?: throw HellobikeException("预下单响应缺少有效金额，请在哈啰核对订单")
        val orders = data.arrayOrNull("orderGoPayList").orEmpty().map { value ->
            val order = value as? JsonObject ?: throw HellobikeException("预下单订单格式异常，请在哈啰核对订单")
            HellobikePayOrder(order.hbText("payNo") ?: throw HellobikeException("预下单响应缺少 payNo，请在哈啰核对订单"),
                order.hbText("payPrice")?.let(HellobikeProtocol::money) ?: throw HellobikeException("预下单响应缺少 payPrice，请在哈啰核对订单"),
                order.hbText("paymentId") ?: throw HellobikeException("预下单响应缺少 paymentId，请在哈啰核对订单"),
                order.hbText("businessType") ?: business)
        }
        if (orders.size != 1 || orders.single().businessType != business || orders.sumOf { it.amount.toBigDecimal() } != amount.toBigDecimal())
            throw HellobikeException("预下单订单或合计不一致，请在哈啰核对订单", officialRequired = true)
        val signingFields = if (product.signingRequired) {
            val period = if (data.hbInt("paySignType") == 1 && data.hbText("periodType") != null &&
                data.hbText("periodNum") != null && data.hbText("maxDeductAmount") != null) jsonObjectOf(
                "period_type" to data["periodType"], "period" to data.hbText("periodNum"), "single_amount" to data["maxDeductAmount"]).toString() else null
            jsonObjectOf("creditModel" to "normal", "actionOrigin" to "RidecardBuying", "periodRuleParams" to period,
                "returnUrl" to "yktwx://hellobike-pay/paysign/success", "requestFromUrl" to "yktwx://hellobike-pay/paysign/fail")
        } else JsonObject(emptyMap())
        return HellobikeCardPurchase(product.name, orders, amount, business, scene, region, signingFields = signingFields)
    }
    override suspend fun cardPayChannels(account: HellobikeAccount, purchase: HellobikeCardPurchase): List<HellobikePayOption> =
        if (purchase.signingFields.isNotEmpty()) listOf(HellobikePayOption(LifePayChannel("102", "支付宝", true, false), purchase.amount))
        else standardPayChannels(account, purchase.region, purchase.orders, purchase.amount, purchase.businessType, purchase.scene, native = true)
    override suspend fun payCard(account: HellobikeAccount, purchase: HellobikeCardPurchase, orders: List<HellobikePayOrder>, option: HellobikePayOption): HellobikePayComponent {
        if (orders.size != 1 || purchase.orders.size != 1 || orders.single() != purchase.orders.single().copy(amount = option.amount))
            throw HellobikeException("购卡订单或应付金额已变化，请刷新")
        // ConfirmPayHelper.b/c preserves StandardCashierRequest.payMoney/payList and sends
        // marketing identifiers separately. Keep the displayed discounted amount in our record.
        val component = standardPay(account, purchase.region, purchase.orders, option, purchase.businessType, native = true, signingFields = purchase.signingFields)
        return component.copy(orders = component.orders.map { it.copy(amount = option.amount) })
    }
    override suspend fun ordinaryProducts(account: HellobikeAccount, region: HellobikeRegion?, position: HellobikePosition?): List<HellobikeCampusProduct> {
        val data = request("tw.ridecard.commodity", JsonObject(HellobikeProtocol.cardFields(account) + cardLocation(region, position) +
            jsonObjectOf("version" to "7.0.75", "channelCodes" to jsonArrayOf(listOf(101000)), "needOptPromo" to true,
                "filterParams" to jsonObjectOf("filterCoBranded" to true))))
        return data.arrayOrNull("packageGroups").orEmpty().flatMapIndexed { groupIndex, value ->
            val group = value as? JsonObject ?: throw HellobikeException("骑行卡商品格式异常")
            if (group.hbInt("businessType") != 1) return@flatMapIndexed emptyList()
            group.arrayOrNull("packages").orEmpty().mapIndexed { index, packageValue ->
                val item = packageValue as? JsonObject ?: throw HellobikeException("骑行卡商品格式异常")
                val raw = JsonObject(jsonObjectOf("channelCode" to group.hbInt("channelCode"), "businessType" to group.hbInt("businessType"),
                    "canBuy" to group.hbInt("canBuy")) + item)
                val price = item.objectOrNull("actualPrice")?.hbText("finalPrice")?.let(HellobikeProtocol::money)
                    ?: throw HellobikeException("骑行卡商品缺少有效价格")
                HellobikeCampusProduct("ordinary:$groupIndex:$index", item.hbText("showName").orEmpty(), price,
                    item.hbText("commodityDesc").orEmpty(), raw, campus = false)
            }
        }
    }
    override suspend fun paymentPaid(account: HellobikeAccount, order: HellobikePayOrder, extension: JsonObject) =
        queryPayment(account, order, extension, native = false)
    override suspend fun cardPaymentPaid(account: HellobikeAccount, order: HellobikePayOrder, extension: JsonObject) =
        queryPayment(account, order, extension, native = true)
    private suspend fun queryPayment(account: HellobikeAccount, order: HellobikePayOrder, extension: JsonObject, native: Boolean): Boolean {
        val fields = if (native) JsonObject(HellobikeProtocol.nativePaymentFields(account) - "currentSdkVersion") else HellobikeProtocol.paymentFields(account)
        val data = request("com.alphapay.facade.orderSingleQuery", JsonObject(fields + jsonObjectOf(
            "guid" to order.guid, "orderId" to order.orderId.takeIf(String::isNotBlank), "businessType" to order.businessType, "extension" to extension)))
        if (data.hbText("guid")?.let { it != order.guid } == true)
            throw HellobikeException("付款查询缺少匹配的订单标识，请在哈啰核对", officialRequired = true)
        // BasePayModule checks the response status for the exact requested payment ID.
        // businessId and order amount are not extra settlement conditions in that flow.
        val status = data["payStatus"] as? JsonPrimitive
        // The official native query checks an integer 1. Strings and SDK callbacks cannot settle a ride.
        return status != null && !status.isString && status.intOrNull == 1
    }
}

internal fun JsonObject.hbText(key: String): String? = (get(key) as? JsonPrimitive)?.takeUnless { it == JsonNull }
    ?.content?.takeIf(String::isNotBlank)
internal fun JsonObject.hbInt(key: String): Int? = (get(key) as? JsonPrimitive)?.content?.takeIf { it.matches(Regex("-?[0-9]+")) }?.toIntOrNull()
internal fun JsonObject.hbBoolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.checkRideIdentity(expected: String) {
    listOf("rideGuid", "originGuid", "orderGuid").mapNotNull(::hbText).forEach {
        if (HellobikeProtocol.normaliseRideGuid(it) != expected) throw HellobikeException("返回订单与本次骑行不一致，请在哈啰核对", officialRequired = true)
    }
}
