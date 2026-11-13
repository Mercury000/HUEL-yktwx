package com.mercury.yktwx.data.life

import com.mercury.yktwx.data.parseObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

internal val fixtureBikeAccount = HellobikeAccount("13000000000", "fixture-token-00000001", "fixture-user-00000001", "fixture-client")
internal val fixtureBikeRegion = HellobikeRegion("0371", "410100", "示例地区")
internal val fixtureBikePosition = HellobikePosition(34.7, 113.6, 12f, 50_000)
internal val fixtureBikeSession = HellobikeSession("fixture-ride-000001", "1234567890", fixtureBikeRegion,
    HellobikePhase.Riding, 1_000_000, sessionId = "fixture-session", startedAtMillis = 1_000_000)
internal val fixtureBikeOrder = HellobikePayOrder("fixture-payment-000001", "2.00")
internal val fixtureBikeDetail = HellobikePayDetail(fixtureBikeSession.rideGuid, "fixture-trade", "2.00",
    HellobikeSettlement.Pending, listOf(fixtureBikeOrder))

internal class BikeRecords : LifeDeviceRecords {
    val values = mutableMapOf<String, String>()
    var failWrite = false
    var onWrite: (() -> Unit)? = null
    override fun read(key: String) = values[key]?.let(::parseObject)
    override fun write(values: Map<String, String>) {
        if (failWrite) throw IllegalStateException("fixture storage failure")
        this.values.putAll(values)
        onWrite?.invoke()
    }
    override fun remove(key: String) { values.remove(key) }
    fun store(account: HellobikeAccount? = fixtureBikeAccount, sessions: List<HellobikeSession> = emptyList(), region: HellobikeRegion? = fixtureBikeRegion): HellobikeAccountStore =
        HellobikeAccountStore(this).also {
            it.saveClientId("fixture-client"); region?.let(it::saveRegion)
            if (account != null) { it.saveAccount(account); it.saveSessions(account.userGuid, sessions) }
        }
}

/** Scripted protocol responses; none of these methods opens a connection or invokes a payment SDK. */
internal open class FakeHellobikeService : HellobikeService {
    var reading: HellobikeRideReading = HellobikeRideReading.NoRide
    var onRead: (suspend (HellobikeAccount) -> HellobikeRideReading)? = null
    var readCalls = 0
    var createCalls = 0
    var closeCalls = 0
    var preCloseCalls = 0
    var payCalls = 0
    var queryCalls = 0
    var prepareCalls = 0
    var smsCalls = 0
    var loginCalls = 0
    var sliderCalls = 0
    var elapsed: () -> Long = { 50_000 }
    var createGate: CompletableDeferred<Unit>? = null
    var createFailure: Exception? = null
    var closeFailure: Exception? = null
    var payFailure: Exception? = null
    var detailFailure: Exception? = null
    var holdClose = false
    var endedOrder = false
    var orderReading: HellobikeOrderReading? = null
    var orderStateFailure: Exception? = null
    val queriedRideGuids = mutableListOf<String>()
    var sms = HellobikeSmsResult()
    var sliderImage = HellobikeSlider(byteArrayOf(), byteArrayOf(), 0, 300, 150, 40, 40)
    var closeReady = true
    var detail = fixtureBikeDetail
    var responseChannel: String? = null
    var queryPaid: (HellobikePayOrder) -> Boolean = { false }
    var beforeCreate: () -> Unit = {}
    var beforeClose: () -> Unit = {}
    var beforePay: () -> Unit = {}
    val queriedGuids = mutableListOf<String>()
    val authRegions = mutableListOf<HellobikeRegion?>()
    val authClientIds = mutableListOf<String>()
    var sliderFailure: Exception? = null
    var sliderGate: CompletableDeferred<Unit>? = null
    val smsOffsets = mutableListOf<Int?>()
    val smsChallenges = mutableListOf<String?>()
    val channels get() = listOf("001", "003", "1201").map { code ->
        HellobikePayOption(LifePayChannel(code, when (code) { "001" -> "支付宝"; "003" -> "哈啰余额"; else -> "银联" }, true, false), "2.00")
    }
    override suspend fun sendCode(phone: String, region: HellobikeRegion?, clientId: String, offset: Int?, challenge: String?): HellobikeSmsResult {
        smsCalls++; authRegions.add(region); authClientIds.add(clientId); smsOffsets.add(offset); smsChallenges.add(challenge); return sms
    }
    override suspend fun slider(phone: String, region: HellobikeRegion?, clientId: String): HellobikeSlider { sliderCalls++; authRegions.add(region); sliderGate?.await(); sliderFailure?.let { throw it }; return sliderImage }
    override suspend fun login(phone: String, code: String, region: HellobikeRegion?, clientId: String, loginGuid: String): HellobikeAccount {
        loginCalls++; authRegions.add(region); return fixtureBikeAccount.copy(phone = phone, clientId = clientId)
    }
    override suspend fun region(position: HellobikePosition) = fixtureBikeRegion
    override suspend fun checkRide(account: HellobikeAccount): HellobikeRideReading { readCalls++; return onRead?.invoke(account) ?: reading }
    override suspend fun prepare(account: HellobikeAccount, bikeNo: String, region: HellobikeRegion, position: HellobikePosition): HellobikePrecheck {
        prepareCalls++; return HellobikePrecheck(bikeNo, fixtureBikeSession.rideGuid, "首个30分钟2元", elapsed(), region, position)
    }
    override suspend fun create(account: HellobikeAccount, precheck: HellobikePrecheck, position: HellobikePosition) {
        beforeCreate(); createCalls++; createGate?.await(); createFailure?.let { throw it }
        reading = HellobikeRideReading.Ride(precheck.orderGuid, precheck.bikeNo, 0, userGuid = account.userGuid)
    }
    override suspend fun orderEnded(account: HellobikeAccount, rideGuid: String) = endedOrder
    override suspend fun orderState(account: HellobikeAccount, rideGuid: String): HellobikeOrderReading {
        queriedRideGuids.add(rideGuid)
        orderStateFailure?.let { throw it }
        return orderReading ?: when {
            endedOrder || (reading as? HellobikeRideReading.Ride)?.status == 2 -> HellobikeOrderReading(30, null)
            (reading as? HellobikeRideReading.Ride)?.status == 0 -> HellobikeOrderReading(20, 0)
            else -> HellobikeOrderReading(null, null)
        }
    }
    override suspend fun endInfo(account: HellobikeAccount, rideGuid: String) = HellobikeEndInfo(rideGuid, true, "2.00", 300)
    override suspend fun preClose(account: HellobikeAccount, session: HellobikeSession, position: HellobikePosition, region: HellobikeRegion,
        poll: Boolean, commit: Boolean): HellobikeCloseReading {
        if (!commit) { preCloseCalls++; return HellobikeCloseReading(!closeReady, closeReady) }
        beforeClose(); closeCalls++; closeFailure?.let { throw it }
        if (!holdClose) reading = HellobikeRideReading.Ride(session.rideGuid, session.bikeNo, 2, userGuid = account.userGuid)
        return HellobikeCloseReading(true, false)
    }
    override suspend fun payDetail(account: HellobikeAccount, session: HellobikeSession): HellobikePayDetail { detailFailure?.let { throw it }; return detail.copy(rideGuid = session.rideGuid) }
    override suspend fun payChannels(account: HellobikeAccount, session: HellobikeSession, detail: HellobikePayDetail) = channels
    override suspend fun pay(account: HellobikeAccount, session: HellobikeSession, orders: List<HellobikePayOrder>, option: HellobikePayOption): HellobikePayComponent {
        beforePay(); payCalls++; payFailure?.let { throw it }
        return HellobikePayComponent(responseChannel ?: option.channel.method, "processing", orders, payload = "fixture-payment-payload")
    }
    override suspend fun paymentPaid(account: HellobikeAccount, order: HellobikePayOrder, extension: JsonObject): Boolean {
        queryCalls++; queriedGuids.add(order.guid); return queryPaid(order)
    }
}

internal class FakeBikeLocation(var point: HellobikePosition = fixtureBikePosition) : HellobikeLocationService {
    var calls = 0
    var failure: Exception? = null
    override suspend fun current(): HellobikePosition { calls++; failure?.let { throw it }; return point }
}
