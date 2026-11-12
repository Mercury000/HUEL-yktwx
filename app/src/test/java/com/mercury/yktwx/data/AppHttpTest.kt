package com.mercury.yktwx.data

import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class AppHttpTest {
    private fun <T> httpsServer(
        protocol: Protocol = Protocol.HTTP_1_1,
        reuseIdleConnections: Boolean = true,
        publicReads: Boolean = false,
        test: (MockWebServer, OkHttpClient) -> T,
    ): T {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val builder = (if (publicReads) testHttpClients.publicRead else testHttpClients.service).newBuilder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .dispatcher(Dispatcher())
            .protocols(if (protocol == Protocol.HTTP_2) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1) else listOf(protocol))
        if (reuseIdleConnections) builder.connectionPool(ConnectionPool())
        val client = builder.build()
        return MockWebServer().use { server ->
            server.useHttps(serverTls.sslSocketFactory())
            server.protocols = client.protocols
            server.start()
            try { test(server, client) }
            finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun serviceConnectionsAreNeverTakenFromAnIdlePool() = httpsServer(reuseIdleConnections = false) { server, client -> runBlocking {
        repeat(2) {
            server.enqueue(MockResponse.Builder().body("fresh").build())
            assertEquals("fresh", client.readResponse(AppHttp.request(server.url("/operate").toString()).build()) {
                it.body.string()
            })
        }
        val connections = List(2) { server.takeRequest(2, TimeUnit.SECONDS)!!.connectionIndex }
        assertEquals(2, connections.distinct().size)
    } }

    @Test fun derivedClientsReuseTlsConnectionAndMultiplexHttp2() = httpsServer(Protocol.HTTP_2) { server, service -> runBlocking {
        val public = service.newBuilder().retryOnConnectionFailure(true).build()
        server.enqueue(MockResponse.Builder().body("first").build())
        assertEquals(Protocol.HTTP_2, service.readResponse(AppHttp.request(server.url("/first").toString()).build()) {
            it.body.string(); it.protocol
        })
        repeat(2) { server.enqueue(MockResponse.Builder().body("next").build()) }
        listOf(service, public).map { client -> async {
            client.readResponse(AppHttp.request(server.url("/next").toString()).build()) { it.body.string() }
        } }.awaitAll()
        val requests = List(3) { server.takeRequest(2, TimeUnit.SECONDS)!! }
        assertEquals(1, requests.map { it.connectionIndex }.distinct().size)
        assertEquals(listOf(0, 1, 2), requests.map { it.exchangeIndex }.sorted())
    } }

    @Test fun gzipIsTransparentAndSizeLimitsApplyAfterDecompression() = httpsServer { server, client -> runBlocking {
        fun compressed(text: String): Buffer {
            val output = ByteArrayOutputStream()
            GZIPOutputStream(output).use { it.write(text.toByteArray()) }
            return Buffer().write(output.toByteArray())
        }
        server.enqueue(MockResponse.Builder().body(compressed("响应数据")).addHeader("Content-Encoding", "gzip").build())
        assertEquals("响应数据", client.readResponse(AppHttp.request(server.url("/").toString()).build()) {
            assertNull(it.header("Content-Encoding"))
            it.readBytesAtMost(100).toString(Charsets.UTF_8)
        })
        assertEquals("gzip", server.takeRequest(2, TimeUnit.SECONDS)!!.headers["Accept-Encoding"])
        server.enqueue(MockResponse.Builder().body(compressed("x".repeat(1_048_577))).addHeader("Content-Encoding", "gzip").build())
        assertEquals(1_048_577, client.readResponse(AppHttp.request(server.url("/").toString()).build()) {
            it.readBytesAtMost(1_048_576).size
        })
    } }

    @Test fun cancellationInterruptsBodyReadAndReleasesDispatcher() = httpsServer { server, client -> runBlocking {
        server.enqueue(MockResponse.Builder().body("blocked body").bodyDelay(3, TimeUnit.SECONDS).build())
        val reading = CompletableDeferred<Unit>()
        val job = launch {
            client.readResponse(AppHttp.request(server.url("/").toString()).build()) {
                reading.complete(Unit)
                it.body.string()
            }
        }
        withTimeout(2_000) { reading.await() }
        withTimeout(1_000) { job.cancelAndJoin() }
        withTimeout(2_000) {
            while (client.dispatcher.runningCallsCount() != 0) { yield(); delay(10) }
        }
    } }

    @Test fun serviceGetAndPostAreNotReplayedAfterAnUncertainResponse() = httpsServer { server, client -> runBlocking {
        for (post in listOf(false, true)) {
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
            val request = AppHttp.request(server.url("/operate").toString()).apply {
                if (post) post(AppHttp.form(mapOf("amount" to "10")))
            }.build()
            assertTrue(runCatching { client.readResponse(request) { it.body.string() } }.exceptionOrNull() is IOException)
        }
        assertEquals(2, server.requestCount)
    } }

    @Test fun queuedCancellationNeverSendsTheWaitingRequest() = httpsServer { server, client -> runBlocking {
        client.dispatcher.maxRequests = 1
        client.dispatcher.maxRequestsPerHost = 1
        server.enqueue(MockResponse.Builder().body("blocked").bodyDelay(3, TimeUnit.SECONDS).build())
        val reading = CompletableDeferred<Unit>()
        val first = launch { client.readResponse(AppHttp.request(server.url("/first").toString()).build()) {
            reading.complete(Unit); it.body.string()
        } }
        withTimeout(2_000) { reading.await() }
        val waiting = launch { client.readResponse(AppHttp.request(server.url("/waiting").toString()).build()) { it.body.string() } }
        withTimeout(2_000) { while (client.dispatcher.queuedCallsCount() == 0) { yield(); delay(10) } }
        withTimeout(1_000) { waiting.cancelAndJoin(); first.cancelAndJoin() }
        withTimeout(2_000) { while (client.dispatcher.runningCallsCount() != 0) { yield(); delay(10) } }
        assertEquals(1, server.requestCount)
        assertEquals(0, client.dispatcher.queuedCallsCount())
    } }

    @Test fun retryHintsCannotReplayAServiceGetOrPost() = httpsServer { server, client -> runBlocking {
        for (post in listOf(false, true)) {
            server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("unavailable").build())
            val request = AppHttp.request(server.url("/operate").toString()).apply {
                if (post) post(AppHttp.form(mapOf("amount" to "10")))
            }.build()
            assertEquals(503, withTimeout(2_000) { client.readResponse(request) { it.code } })
        }
        server.enqueue(MockResponse.Builder().code(421).body("misdirected").build())
        assertTrue(withTimeout(2_000) {
            runCatching { client.readResponse(AppHttp.request(server.url("/operate").toString()).build()) { it.code } }.exceptionOrNull()
        } is IOException)
        assertEquals(3, server.requestCount)
    } }

    @Test fun callDeadlineInterruptsWaitingForResponseHeaders() = httpsServer { server, client -> runBlocking {
        server.enqueue(MockResponse.Builder().body("late").headersDelay(3, TimeUnit.SECONDS).build())
        val bounded = client.newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        val failure = withTimeout(2_000) {
            runCatching { bounded.readResponse(AppHttp.request(server.url("/").toString()).build()) { it.body.string() } }.exceptionOrNull()
        }
        assertTrue(failure is java.io.InterruptedIOException)
    } }

    @Test fun callDeadlineIncludesDispatcherQueueAndNeverSendsExpiredRequest() = httpsServer { server, client -> runBlocking {
        client.dispatcher.maxRequests = 1
        client.dispatcher.maxRequestsPerHost = 1
        server.enqueue(MockResponse.Builder().body("blocked").bodyDelay(3, TimeUnit.SECONDS).build())
        val reading = CompletableDeferred<Unit>()
        val first = launch { client.readResponse(AppHttp.request(server.url("/first").toString()).build()) {
            reading.complete(Unit); it.body.string()
        } }
        try {
            withTimeout(2_000) { reading.await() }
            val bounded = client.newBuilder().callTimeout(300, TimeUnit.MILLISECONDS).build()
            val failure = withTimeout(1_500) {
                runCatching { bounded.readResponse(AppHttp.request(server.url("/expired").toString()).build()) { it.code } }.exceptionOrNull()
            }
            assertTrue("Queue deadline must be a transport failure, got $failure", failure is java.io.InterruptedIOException)
        } finally { first.cancelAndJoin() }
        withTimeout(2_000) { while (client.dispatcher.runningCallsCount() != 0) { delay(10) } }
        assertEquals(1, server.requestCount)
        assertEquals(0, client.dispatcher.queuedCallsCount())
    } }

    @Test fun largeRetryAfterCannotBreakServiceResponseHandling() = httpsServer { server, client -> runBlocking {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "2147483648").body("unavailable").build())
        val result = client.readResponse(AppHttp.request(server.url("/operate").toString()).build()) { it.code to it.body.string() }
        assertEquals(503 to "unavailable", result)
        assertEquals(1, server.requestCount)
    } }

    @Test fun largePublicRetryDelaysAreReturnedWithoutReplayOrParserFailure() = httpsServer(publicReads = true) { server, client -> runBlocking {
        for (status in listOf(408, 503)) {
            server.enqueue(MockResponse.Builder().code(status).addHeader("Retry-After", "2147483648").body("wait").build())
            assertEquals(status to "wait", client.readResponse(AppHttp.request(server.url("/public").toString()).build()) {
                it.code to it.body.string()
            })
        }
        assertEquals(2, server.requestCount)
    } }

    @Test fun publicReadsStillRecoverFromAnImmediateRetryHint() = httpsServer(publicReads = true) { server, client -> runBlocking {
        server.enqueue(MockResponse.Builder().code(503).addHeader("Retry-After", "0").body("wait").build())
        server.enqueue(MockResponse.Builder().body("recovered").build())
        assertEquals("recovered", client.readResponse(AppHttp.request(server.url("/public").toString()).build()) { it.body.string() })
        assertEquals(2, server.requestCount)
    } }

    @Test fun zeroCallDeadlineAllowsStreamingAndNullableResults() = httpsServer { server, client -> runBlocking {
        server.enqueue(MockResponse.Builder().body("body").bodyDelay(300, TimeUnit.MILLISECONDS).build())
        val streaming = client.newBuilder().callTimeout(0, TimeUnit.SECONDS).build()
        assertNull(withTimeout(2_000) {
            streaming.readResponse(AppHttp.request(server.url("/download").toString()).build()) { it.body.string(); null }
        })
    } }

    @Test fun redirectsAreReturnedAndCookiesDoNotEnterLaterRequests() = httpsServer { server, client -> runBlocking {
        server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "https://another.example.com/")
            .addHeader("Set-Cookie", "session=private; Path=/").body("").build())
        assertEquals(302, client.readResponse(AppHttp.request(server.url("/").toString())
            .header("synjones-auth", "bearer fixture").build()) { it.code })
        server.enqueue(MockResponse.Builder().body("ok").build())
        client.readResponse(AppHttp.request(server.url("/public").toString()).build()) { it.body.string() }
        server.takeRequest(2, TimeUnit.SECONDS)
        val public = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertNull(public.headers["Cookie"])
        assertNull(public.headers["synjones-auth"])
        assertEquals(2, server.requestCount)
    } }

    @Test fun updateRedirectsValidateEveryHopAndCloseEveryResponse() = runBlocking {
        val calls = mutableListOf<HttpFixture>()
        val closed = mutableListOf<String>()
        val http = fixtureUpdateHttp { url -> object : HttpFixture(url) {
            override fun statusCode() = if (url.path == "/latest") 302 else 200
            override fun header(name: String?) = if (name == "Location") "https://cdn.example.com/manifest" else null
            override fun bodyStream() = "{}".byteInputStream()
            override fun close() { closed += url.toString() }
        }.also(calls::add) }
        assertEquals("{}", http.json("https://authority.example.com/latest"))
        assertEquals(listOf("authority.example.com", "cdn.example.com"), calls.map { it.url.host })
        assertEquals(calls.map { it.url.toString() }, closed)
        calls.forEach {
            assertNull(it.request.header("synjones-auth")); assertNull(it.request.header("Authorization"))
        }
        for (location in listOf("http://example.com/manifest", "https://user@example.com/manifest", "https://example.com:8443/manifest")) {
            var closed = false
            var count = 0
            val invalid = fixtureUpdateHttp { url -> count++; object : HttpFixture(url) {
                override fun statusCode() = 302
                override fun header(name: String?) = location
                override fun close() { closed = true }
            } }
            assertTrue(runCatching { invalid.json("https://authority.example.com/latest") }.isFailure)
            assertEquals(1, count); assertTrue(closed)
        }
    }
}
