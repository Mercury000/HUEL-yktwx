package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import com.mercury.yktwx.BuildConfig
import com.google.crypto.tink.subtle.Ed25519Verify
import java.net.URI
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.Call
import okhttp3.Response

internal data class UpdateRequest(val packageName: String, val versionCode: Int, val sdkInt: Int,
    val channel: String = BuildConfig.UPDATE_CHANNEL)
internal data class UpdateRelease(
    val versionCode: Int, val versionName: String, val notes: String, val downloadPageUrl: String,
    val channel: String = "stable", val publishedAt: Instant? = null,
    val maxForcedVersionCode: Int = 0, val policyRevision: Long = 0,
    val urls: List<String> = emptyList(), val sha256: String = "", val size: Long = 0,
    val packageName: String = BuildConfig.APPLICATION_ID, val minSdk: Int = 34,
    val expiresAt: Instant? = null, val signedManifest: String = "",
) {
    fun required(current: Int) = current <= maxForcedVersionCode && versionCode > current
}
internal enum class UpdateCheckReason { UpdateAvailable, CurrentOrNewer, NoPublishedRelease, NoCompatibleRelease }
internal data class UpdateCheckResult(val reason: UpdateCheckReason, val release: UpdateRelease? = null)

internal fun httpsUri(value: String): URI = URI(value).also {
    require(it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null &&
        (it.port == -1 || it.port == 443)) { "更新地址无效" }
}
internal fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

internal class UpdateHttpFailure(val status: Int) : java.io.IOException("更新来源暂时不可用")

/** Dedicated, cancellable public transport. No campus headers, cookies or account data. */
internal class UpdateHttp(private val metadataTimeoutMillis: Long = 1_500,
    private val client: Call.Factory = AppHttp.update) {
    suspend fun <T> read(url: String, consume: (Response) -> T): T {
        var target = httpsUri(url)
        repeat(6) { redirect ->
            currentCoroutineContext().ensureActive()
            val request = AppHttp.request(target.toString())
                .header("Cache-Control", "no-cache, no-store")
                .header("Pragma", "no-cache")
                .header("User-Agent", "CaiXiaoTong-AppUpdate/1")
                .header("Accept", "application/vnd.github.raw+json, application/json, */*").build()
            var next: URI? = null
            val result = client.readResponse(request) { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    require(redirect < 5) { "更新地址重定向过多" }
                    next = httpsUri(target.resolve(response.header("Location") ?: error("更新地址无效")).toString())
                    null
                } else {
                    if (!response.isSuccessful) throw UpdateHttpFailure(response.code)
                    consume(response)
                }
            }
            if (next == null) {
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
            target = next
        }
        error("更新地址无效")
    }
    suspend fun json(url: String): String = withTimeoutOrNull(metadataTimeoutMillis) {
        read(url) { response ->
            val bytes = response.readBytesAtMost(1_048_576)
            require(bytes.size <= 1_048_576) { "更新信息过大" }
            val body = bytes.toString(Charsets.UTF_8)
            val json = parseObject(body)
            if (json.stringOrDefault("encoding") == "base64") {
                Base64.getMimeDecoder().decode(json.stringValue("content")).toString(Charsets.UTF_8)
            } else body
        }
    } ?: throw java.net.SocketTimeoutException("更新来源响应超时")
}

/** Sign exact payload bytes; JSON key ordering cannot change the signed meaning. */
internal class UpdateManifestVerifier(publicKey: String = BuildConfig.UPDATE_PUBLIC_KEY) {
    // Android's Ed25519 KeyFactory may only handle Keystore keys. This constructor uses
    // Tink's portable verifier without changing the process-wide security providers.
    private val verifier = Base64.getDecoder().decode(publicKey).let { encoded ->
        val header = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        require(encoded.size == header.size + Ed25519Verify.PUBLIC_KEY_LEN &&
            encoded.copyOfRange(0, header.size).contentEquals(header)) { "更新验签公钥格式无效" }
        Ed25519Verify(encoded.copyOfRange(header.size, encoded.size))
    }
    fun payload(envelope: String): Pair<JsonObject, String> {
        val wrapper = parseObject(envelope)
        require(wrapper.stringValue("algorithm") == "Ed25519")
        val bytes = Base64.getDecoder().decode(wrapper.stringValue("payload"))
        require(bytes.size <= 524_288)
        try { verifier.verify(Base64.getDecoder().decode(wrapper.stringValue("signature")), bytes) }
        catch (error: GeneralSecurityException) { throw IllegalArgumentException("更新清单签名无效", error) }
        return parseObject(bytes.toString(Charsets.UTF_8)) to sha256(bytes)
    }
    fun manifest(envelope: String, channel: String): Pair<UpdateRelease, String> {
        val (json, digest) = payload(envelope)
        val release = parse(json)
        require(release.channel == channel) { "更新渠道不匹配" }
        return release.copy(signedManifest = envelope) to digest
    }
    companion object {
        private fun integer(json: JsonObject, name: String): Long = json.strictLongValue(name)
        fun parse(json: JsonObject): UpdateRelease {
            require(integer(json, "schemaVersion") == 1L)
            val channel = json.stringValue("channel"); require(channel in listOf("stable", "beta"))
            val version = integer(json, "versionCode"); require(version in 1..Int.MAX_VALUE)
            val forced = integer(json, "maxForcedVersionCode"); require(forced in 0 until version)
            val revision = integer(json, "policyRevision"); require(revision > 0)
            val hash = json.stringValue("sha256"); require(hash.matches(Regex("[a-f0-9]{64}")))
            val array = json.arrayValue("urls"); require(array.size in 6..20)
            val urls = (0 until array.size).map { array.stringValue(it).also(::httpsUri) }
            require(urls.distinct().size == urls.size)
            require(urls.map(::httpsUri).map { it.host.lowercase() }.filter { it != "github.com" }.distinct().size >= 5)
            val size = integer(json, "size"); require(size in 1..536_870_912L)
            val minSdk = integer(json, "minSdk"); require(minSdk in 1..Int.MAX_VALUE)
            val name = json.stringValue("versionName"); require(name.isNotBlank() && name.length <= 100)
            val releaseUrl = json.stringValue("releaseUrl").also(::httpsUri)
            val published = json.stringOrDefault("publishedAt").takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val expires = Instant.parse(json.stringValue("expiresAt"))
            return UpdateRelease(version.toInt(), name, json.stringValue("changelog"), releaseUrl,
                channel, published, forced.toInt(), revision, urls, hash, size,
                json.stringValue("packageName"), minSdk.toInt(), expires)
        }
    }
}

internal class UpdateSourceUnavailable(val notPublished: Boolean = false) : java.io.IOException("暂时无法获取更新清单")

internal class AppUpdateApi(private val http: UpdateHttp = UpdateHttp(),
    private val verifier: UpdateManifestVerifier = UpdateManifestVerifier(),
    private val sourceTimeoutMillis: Long = 2_500,
    private val now: () -> Instant = Instant::now) {
    private data class Candidate(val release: UpdateRelease, val digest: String, val authority: Boolean)
    suspend fun check(request: UpdateRequest, store: UpdateCheckStore): UpdateCheckResult = withTimeoutOrNull(3_000) {
        store.repository.awaitReady()
        checkChannels(request, store)
    } ?: throw java.net.SocketTimeoutException("更新检查超时")

    private suspend fun checkChannels(request: UpdateRequest, store: UpdateCheckStore): UpdateCheckResult = coroutineScope {
        val channels = if (request.channel == "beta") listOf("beta", "stable") else listOf("stable")
        val outcomes = channels.map { channel -> async {
            try { resolve(channel, store, request) to false }
            catch (error: UpdateSourceUnavailable) { null to error.notPublished }
        } }.awaitAll()
        val releases = outcomes.mapNotNull { it.first }
        if (releases.isEmpty()) {
            if (outcomes.all { it.second }) return@coroutineScope UpdateCheckResult(UpdateCheckReason.NoPublishedRelease)
            throw UpdateSourceUnavailable()
        }
        require(releases.all { it.packageName == request.packageName }) { "更新包名不匹配" }
        val compatible = releases.filter { it.minSdk <= request.sdkInt }
        val boundary = maxOf(releases.maxOf { it.maxForcedVersionCode }, store.forcedBoundary())
        val best = compatible.maxByOrNull { it.versionCode }?.let {
            it.copy(maxForcedVersionCode = boundary.coerceAtMost(it.versionCode - 1))
        }
        if (request.versionCode <= boundary && (best == null || best.versionCode <= request.versionCode))
            error("强制更新暂时没有兼容的安装包")
        when {
            best == null -> UpdateCheckResult(UpdateCheckReason.NoCompatibleRelease)
            best.versionCode <= request.versionCode -> UpdateCheckResult(UpdateCheckReason.CurrentOrNewer)
            else -> UpdateCheckResult(UpdateCheckReason.UpdateAvailable, best)
        }
    }
    private suspend fun resolve(channel: String, store: UpdateCheckStore, request: UpdateRequest): UpdateRelease = coroutineScope {
        val responses = Channel<Pair<Int, Candidate?>>(Channel.UNLIMITED)
        val invalidResponse = java.util.concurrent.atomic.AtomicBoolean(false)
        val authorityMissing = java.util.concurrent.atomic.AtomicBoolean(false)
        val jobs = sources(channel, "latest.json").mapIndexed { index, source -> launch {
            var pointerReceived = false
            val result = try {
                val pointerBody = http.json(cacheBust(source))
                pointerReceived = true
                val (pointer, _) = verifier.payload(pointerBody)
                require(pointer.strictLongValue("schemaVersion") == 1L && pointer.stringValue("channel") == channel)
                val pointerRevision = pointer.strictLongValue("policyRevision")
                require(Instant.parse(pointer.stringValue("expiresAt")) > now()) { "更新指针已过期" }
                val path = pointer.stringValue("manifestPath")
                require(path.matches(Regex("manifests/[1-9][0-9]*-[a-f0-9]{64}\\.json")))
                val body = http.json(sources(channel, path)[index])
                require(sha256(body.toByteArray()) == pointer.stringValue("manifestSha256"))
                val (release, digest) = verifier.manifest(body, channel)
                require(release.packageName == request.packageName) { "更新包名不匹配" }
                require(release.policyRevision == pointerRevision)
                require(release.expiresAt!! > now()) { "更新清单已过期" }
                Candidate(release, digest, index == 0)
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: UpdateHttpFailure) {
                  if (index == 0 && !pointerReceived && error.status == 404) authorityMissing.set(true)
                  null
              }
              catch (_: java.io.IOException) { null }
              catch (_: Exception) { invalidResponse.set(true); null }
            responses.send(index to result)
        } }
        val candidates = mutableListOf<Candidate>()
        var received = 0
        var authorityFinished = false
        suspend fun receiveResponse() {
            val (index, candidate) = responses.receive()
            candidate?.let { candidates += it }
            if (index == 0) authorityFinished = true
            received++
        }
        try {
            withTimeoutOrNull(sourceTimeoutMillis) {
                while (received < jobs.size) {
                    receiveResponse()
                    if (candidates.isNotEmpty() || (authorityFinished && authorityMissing.get())) {
                        break
                    }
                }
            }
            if (received < jobs.size && (candidates.isNotEmpty() || (authorityFinished && authorityMissing.get()))) {
                // The convergence window starts after the first result, independently of the
                // initial wait budget. Two fast mirrors may share the same stale cache.
                withTimeoutOrNull(300) {
                    while (received < jobs.size) receiveResponse()
                }
            }
        } finally { jobs.forEach { it.cancel() } }
        if (candidates.isEmpty()) {
            require(!invalidResponse.get()) { "更新清单未通过校验" }
            throw UpdateSourceUnavailable(authorityMissing.get())
        }
        val conflicts = candidates.groupBy { it.release.policyRevision }
            .filterValues { group -> group.map { it.digest }.distinct().size > 1 }.keys
        val chosen = candidates.firstOrNull { it.authority }
            ?: candidates.filter { it.release.policyRevision !in conflicts }.maxByOrNull { it.release.policyRevision }
            ?: error("更新清单存在冲突")
        require(chosen.release.policyRevision !in conflicts) { "更新清单存在冲突" }
        store.acceptPolicy(channel, chosen.release, chosen.digest)
        chosen.release
    }
    companion object {
        const val REPOSITORY = "Mercury000/HUEL-yktwx"
        fun sources(channel: String, path: String): List<String> {
            val file = "updates/$channel/$path"
            return listOf(
                "https://api.github.com/repos/$REPOSITORY/contents/$file?ref=main",
                "https://raw.githubusercontent.com/$REPOSITORY/main/$file",
                "https://cdn.jsdelivr.net/gh/$REPOSITORY@main/$file",
                "https://fastly.jsdelivr.net/gh/$REPOSITORY@main/$file",
                "https://gcore.jsdelivr.net/gh/$REPOSITORY@main/$file",
                "https://testingcf.jsdelivr.net/gh/$REPOSITORY@main/$file",
                "https://cdn.statically.io/gh/$REPOSITORY/main/$file")
        }
        fun cacheBust(url: String) = url + (if ('?' in url) "&" else "?") + "t=${System.currentTimeMillis()}"
    }
}
