package com.mercury.yktwx.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

internal enum class UpdateDownloadPhase { Idle, SelectingSource, Downloading, Verifying, Ready, NeedsAuthorization, Installing, Failed }
internal data class UpdateDownloadState(val phase: UpdateDownloadPhase = UpdateDownloadPhase.Idle,
    val percent: Int? = null, val error: String? = null, val retryInstallation: Boolean = false,
    val attempt: Int = 1) {
    val busy get() = phase in listOf(UpdateDownloadPhase.SelectingSource, UpdateDownloadPhase.Downloading, UpdateDownloadPhase.Verifying)
}

/** Matching bytes cannot become compatible by downloading them from another mirror. */
internal class UpdateApkIdentityFailure(message: String) : IllegalArgumentException(message)

internal class AppUpdateInstaller(private val context: Context, private val http: UpdateHttp = UpdateHttp(),
    private val sourceSelectionMillis: Long = 3_000,
    private val contentUri: (File) -> Uri = {
        FileProvider.getUriForFile(context, "${context.packageName}.updates", it)
    }) {
    private val directory = File(context.filesDir, "app-updates").apply { mkdirs() }
    private val downloadLock = Mutex()
    fun apk(release: UpdateRelease) = File(directory, "${release.versionCode}-${release.sha256}.apk")
    suspend fun download(release: UpdateRelease, progress: (UpdateDownloadState) -> Unit): File = withContext(Dispatchers.IO) {
        downloadLock.withLock { downloadLocked(release, progress) }
    }

    private suspend fun downloadLocked(release: UpdateRelease, progress: (UpdateDownloadState) -> Unit): File {
        require(release.sha256.matches(Regex("[a-f0-9]{64}")) && release.urls.size >= 6 && release.size > 0) { "更新清单无效" }
        verifyReleaseCompatibility(release)
        val destination = apk(release)
        if (destination.exists()) {
            try { verify(release, destination); return destination }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: UpdateApkIdentityFailure) { throw error }
            catch (_: Exception) { }
        }
        // Only one version/hash plan is run by the owning view model.
        directory.listFiles()?.filter { it != destination }?.forEach { it.delete() }
        val sources = if (sourceSelectionMillis > 0) {
            progress(UpdateDownloadState(UpdateDownloadPhase.SelectingSource))
            preferResponsiveSource(release)
        } else release.urls
        var last: Exception? = null
        for ((index, url) in sources.withIndex()) {
            currentCoroutineContext().ensureActive()
            // A cancelled callback can still be closing its stream when a new attempt starts.
            val temporary = File(directory, destination.name + "-${UUID.randomUUID()}.download")
            try {
                progress(UpdateDownloadState(UpdateDownloadPhase.Downloading, 0, attempt = index + 1))
                val downloadContext = currentCoroutineContext()
                http.read(url) { response ->
                    try {
                        val declared = response.contentLengthLong
                        require(declared < 0 || declared == release.size) { "安装包大小不符" }
                        response.inputStream.use { input -> FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L; var lastProgress = 0L
                            while (true) {
                                downloadContext.ensureActive()
                                val count = input.read(buffer); if (count < 0) break
                                downloadContext.ensureActive()
                                total += count
                                require(total <= release.size) { "安装包大小不符" }
                                output.write(buffer, 0, count)
                                val now = System.nanoTime()
                                if (now - lastProgress >= 300_000_000) {
                                    progress(UpdateDownloadState(UpdateDownloadPhase.Downloading,
                                        (total * 100 / release.size).toInt().coerceAtMost(99), attempt = index + 1))
                                    lastProgress = now
                                }
                            }
                            require(total == release.size) { "安装包下载不完整" }
                            output.fd.sync()
                        } }
                    } finally {
                        // Cancellation resumes the caller immediately; also clean up after
                        // the callback closes the file (needed when an open file cannot be deleted).
                        if (!downloadContext.isActive) temporary.delete()
                    }
                }
                progress(UpdateDownloadState(UpdateDownloadPhase.Verifying, attempt = index + 1))
                verify(release, temporary)
                check(temporary.renameTo(destination)) { "无法保存安装包" }
                return destination
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: UpdateApkIdentityFailure) { throw error }
              catch (error: Exception) { last = error }
              finally { temporary.delete() }
        }
        throw java.io.IOException("所有下载来源均失败：${last?.message ?: "网络连接失败"}，请重试", last)
    }

    private fun verifyReleaseCompatibility(release: UpdateRelease) {
        identity(release.minSdk <= android.os.Build.VERSION.SDK_INT, "当前系统不支持此更新，请重新检查")
        identity(release.packageName == context.packageName, "安装包包名不匹配")
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        identity(release.versionCode.toLong() > installed.longVersionCode, "安装包版本不匹配")
        // The signed manifest can reject incompatible certificates before spending data on the APK.
        release.signerSha256?.let { expected ->
            val signers = installed.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
            identity(signers == setOf(expected), "安装包签名与当前应用不一致")
        }
    }

    /** Race a small APK prefix, rather than equating metadata latency or HTTP 200 with a usable APK. */
    private suspend fun preferResponsiveSource(release: UpdateRelease): List<String> = coroutineScope {
        val responses = Channel<String?>(Channel.UNLIMITED)
        val jobs = release.urls.map { url -> launch {
            val available = try {
                http.read(url) { response ->
                    val declared = response.contentLengthLong
                    require(declared < 0 || declared == release.size) { "安装包大小不符" }
                    val prefix = response.inputStream.readNBytes(minOf(release.size, 16_384L).toInt())
                    require(prefix.size >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte() &&
                        prefix[2] == 0x03.toByte() && prefix[3] == 0x04.toByte()) { "下载来源未返回安装包" }
                }
                url
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { null }
            responses.send(available)
        } }
        val preferred = try {
            withTimeoutOrNull(sourceSelectionMillis) {
                repeat(release.urls.size) { responses.receive()?.let { return@withTimeoutOrNull it } }
                null
            }
        } finally { jobs.forEach { it.cancel() } }
        if (preferred == null) throw java.io.IOException("暂时没有可用的下载来源，请检查网络后重试")
        listOf(preferred) + release.urls.filter { it != preferred }
    }

    /** Re-hash on every install and authorization return; never trust a historical flag. */
    suspend fun verify(release: UpdateRelease, file: File = apk(release)) = withContext(Dispatchers.IO) {
        try {
            identity(release.minSdk <= android.os.Build.VERSION.SDK_INT, "当前系统不支持此更新，请重新检查")
            require(file.isFile && file.length() == release.size) { "安装包不存在或大小不符" }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer)
                    if (count < 0) break; digest.update(buffer, 0, count) }
            }
            require(MessageDigest.isEqual(digest.digest(), release.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())) { "安装包校验失败" }
            val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            @Suppress("DEPRECATION")
            val candidate = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw UpdateApkIdentityFailure("无法读取安装包")
            val installed = context.packageManager.getPackageInfo(context.packageName, flags)
            identity(candidate.packageName == context.packageName && candidate.packageName == release.packageName, "安装包包名不匹配")
            identity(candidate.longVersionCode == release.versionCode.toLong() && candidate.longVersionCode > installed.longVersionCode, "安装包版本不匹配")
            val oldSigners = installed.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
            val newSigners = candidate.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
            identity(oldSigners.isNotEmpty() && oldSigners == newSigners, "安装包签名与当前应用不一致")
            identity(release.signerSha256 == null || newSigners == setOf(release.signerSha256), "安装包签名与更新清单不一致")
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { file.delete(); throw error }
    }
    private fun identity(valid: Boolean, message: String) {
        if (!valid) throw UpdateApkIdentityFailure(message)
    }
    fun authorized() = context.packageManager.canRequestPackageInstalls()
    fun authorizationIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"))
    fun installIntent(release: UpdateRelease): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(contentUri(apk(release)),
            "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_RETURN_RESULT, true)
    }
}
