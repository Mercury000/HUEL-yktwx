package com.mercury.yktwx.data

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Process-wide clients share the dispatcher. Repeatable reads and updates also share a reusable
 * connection pool. Authenticated service calls deliberately do not retain idle connections:
 * their GETs can issue payment codes or operate devices, so a stale socket must never force a
 * replay of a request that may already have reached the server.
 * Credentials belong to individual requests, never to a shared interceptor or cookie jar.
 */
@Singleton
internal class AppHttp @Inject constructor() {
    // retryOnConnectionFailure(false) does not disable 503/Retry-After or coalesced
    // HTTP/2 421 follow-ups. Keep service operations at one wire attempt in both cases.
    private val noServiceReplay = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        when {
            response.code == 421 -> {
                response.close()
                throw IOException("Misdirected service request (HTTP 421)")
            }
            response.code == 503 ->
                response.newBuilder().removeHeader("Retry-After").build()
            else -> response
        }
    }
    // OkHttp 5.3 parses numeric retry delays as Int. Large server values must mean "wait",
    // rather than throwing NumberFormatException on the dispatcher thread.
    private val boundedPublicRetryDelay = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        val delay = response.header("Retry-After")
        if (response.code in setOf(408, 503) && !delay.isNullOrEmpty() &&
            delay.all { it in '0'..'9' } && delay.toIntOrNull() == null) {
            response.newBuilder().header("Retry-After", Int.MAX_VALUE.toString()).build()
        } else response
    }
    // Even a GET may issue a payment code or operate a device. Never replay service calls, and
    // never hand one a socket left idle across a foreground pause or automatic page refresh.
    private val disposableServicePool = ConnectionPool(0, 1, TimeUnit.SECONDS)
    private val reusableReadPool = ConnectionPool()

    val service: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectionPool(disposableServicePool)
        .addNetworkInterceptor(noServiceReplay)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    // Public, repeatable reads may recover from stale pooled connections and alternate IPs.
    val publicRead: OkHttpClient = service.newBuilder()
        .apply { networkInterceptors().remove(noServiceReplay) }
        .addNetworkInterceptor(boundedPublicRetryDelay)
        .connectionPool(reusableReadPool)
        .retryOnConnectionFailure(true).build()
    val update: OkHttpClient = publicRead.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        // Metadata has its own deadline; APK transfers use a stall timeout, not a size deadline.
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    companion object {
        fun request(url: String): Request.Builder = Request.Builder().url(url)
            .header("Cache-Control", "no-store")
            .header("Accept", "application/json")

        fun form(fields: Map<String, String>, contentType: String? = null): RequestBody {
            val form = FormBody.Builder(Charsets.UTF_8).apply { fields.forEach { (key, value) -> add(key, value) } }.build()
            if (contentType == null) return form
            return object : RequestBody() {
                override fun contentType() = contentType.toMediaType()
                override fun contentLength() = form.contentLength()
                override fun writeTo(sink: BufferedSink) = form.writeTo(sink)
            }
        }
    }
}

/** Bound queueing as well as execution: OkHttp's asynchronous call timeout starts only when
 * the dispatcher runs the call. A zero timeout still permits streaming APK transfers.
 */
internal suspend fun <T> Call.Factory.readResponse(request: Request, consume: (Response) -> T): T {
    require(request.url.isHttps && request.url.username.isEmpty() && request.url.password.isEmpty())
    currentCoroutineContext().ensureActive()
    val call = newCall(request)
    val timeoutNanos = call.timeout().timeoutNanos()
    if (timeoutNanos == 0L) return call.consumeResponse(consume)
    try {
        return withTimeout(timeoutNanos.nanoseconds) { call.consumeResponse(consume) }
    } catch (expired: TimeoutCancellationException) {
        // Parent cancellation must remain cancellation; only this call's deadline is an I/O error.
        currentCoroutineContext().ensureActive()
        throw InterruptedIOException("timeout").apply { initCause(expired) }
    }
}

/** Consume and close on the dispatcher. Cancellation covers queued calls and blocked body reads. */
private suspend fun <T> Call.consumeResponse(consume: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        val call = this
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        continuation.context.ensureActive()
                        consume(it).also { continuation.context.ensureActive() }
                    }
                    continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

/** Limit decoded bytes, including transparently decompressed gzip responses. */
internal fun Response.readBytesAtMost(limit: Int): ByteArray = body.byteStream().readNBytes(limit + 1)
