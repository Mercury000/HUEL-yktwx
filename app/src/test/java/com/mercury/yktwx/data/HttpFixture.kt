package com.mercury.yktwx.data

import com.mercury.yktwx.data.life.Hui798Api
import com.mercury.yktwx.data.life.LifeDeviceApi
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Scripted responses consumed through a real OkHttp dispatcher and production request builders. */
internal abstract class HttpFixture(val url: URL) {
    lateinit var request: Request
    open fun statusCode(): Int = 200
    open fun bodyStream(): InputStream = ByteArrayInputStream(byteArrayOf())
    open fun errorBodyStream(): InputStream? = bodyStream()
    open fun requestBodyStream(): OutputStream = ByteArrayOutputStream()
    open fun contentType(): String? = null
    open fun contentLength(): Long = -1
    open fun header(name: String?): String? = null
    open fun close() {}
}

internal fun fixtureHttp(respond: (URL) -> HttpFixture): Call.Factory {
    val active = ConcurrentHashMap<Call, HttpFixture>()
    return AppHttp.service.newBuilder()
        .eventListener(object : EventListener() {
            override fun canceled(call: Call) { active[call]?.close() }
        })
        .addInterceptor { chain ->
            val fixture = respond(chain.request().url.toUrl())
            active[chain.call()] = fixture
            try {
                val body = chain.request().body
                fixture.request = chain.request().newBuilder().apply {
                    body?.contentType()?.let { header("Content-Type", it.toString()) }
                }.build()
                if (body != null) {
                    val buffer = Buffer()
                    body.writeTo(buffer)
                    fixture.requestBodyStream().write(buffer.readByteArray())
                }
                if (chain.call().isCanceled()) fixture.close()
                val status = fixture.statusCode()
                val source = object : Source {
                    private var input: InputStream? = null
                    override fun timeout() = Timeout.NONE
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val stream = input ?: (if (status in 200..299) fixture.bodyStream() else fixture.errorBodyStream()
                            ?: ByteArrayInputStream(byteArrayOf())).also { input = it }
                        val bytes = ByteArray(minOf(byteCount, 8192).toInt())
                        val count = stream.read(bytes)
                        if (count < 0) return -1
                        sink.write(bytes, 0, count)
                        return count.toLong()
                    }
                    override fun close() {
                        try { input?.close() } finally { fixture.close(); active.remove(chain.call()) }
                    }
                }.buffer()
                Response.Builder().request(fixture.request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                    .apply {
                        fixture.header("Location")?.let { header("Location", it) }
                        (fixture.header("Content-Type") ?: fixture.contentType())?.let { header("Content-Type", it) }
                    }
                    .body(object : ResponseBody() {
                        override fun contentType() = fixture.contentType()?.toMediaTypeOrNull()
                        override fun contentLength() = fixture.contentLength()
                        override fun source() = source
                    }).build()
            } catch (error: Exception) {
                fixture.close(); active.remove(chain.call())
                throw error
            }
        }.build()
}

internal fun fixtureYktApi(respond: (URL) -> HttpFixture) = YktApi(fixtureHttp(respond))
internal fun fixtureCardServiceApi(respond: (URL) -> HttpFixture) = CardServiceApi(fixtureHttp(respond))
internal fun fixtureRechargeApi(respond: (URL) -> HttpFixture) = RechargeApi(fixtureHttp(respond))
internal fun fixtureFeeApi(respond: (URL) -> HttpFixture) = FeeApi(fixtureHttp(respond))
internal fun fixtureProfileApi(respond: (URL) -> HttpFixture): ProfileApi {
    val client = fixtureHttp(respond)
    return ProfileApi(client, client)
}
internal fun fixtureUpdateHttp(metadataTimeoutMillis: Long = UpdateHttp.DEFAULT_METADATA_TIMEOUT_MILLIS, respond: (URL) -> HttpFixture) =
    UpdateHttp(metadataTimeoutMillis, fixtureHttp(respond))
internal fun fixtureLifeDeviceApi(secret: String, respond: (URL) -> HttpFixture, clock: () -> Long = System::currentTimeMillis) =
    LifeDeviceApi(secret, fixtureHttp(respond), clock)
internal fun fixtureHui798Api(gateway: String, salt: String, respond: (URL) -> HttpFixture) =
    Hui798Api(gateway, salt, fixtureHttp(respond))
