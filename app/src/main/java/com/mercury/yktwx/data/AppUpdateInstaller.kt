package com.mercury.yktwx.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

internal enum class UpdateDownloadPhase { Idle, Downloading, Verifying, Ready, NeedsAuthorization, Installing, Failed }
internal data class UpdateDownloadState(val phase: UpdateDownloadPhase = UpdateDownloadPhase.Idle,
    val percent: Int? = null, val error: String? = null, val retryInstallation: Boolean = false) {
    val busy get() = phase in listOf(UpdateDownloadPhase.Downloading, UpdateDownloadPhase.Verifying)
}

internal class AppUpdateInstaller(private val context: Context, private val http: UpdateHttp = UpdateHttp(),
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
        require(release.sha256.matches(Regex("[a-f0-9]{64}")) && release.urls.size >= 6) { "更新清单无效" }
        val destination = apk(release)
        if (destination.exists()) {
            try { verify(release, destination); return destination }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
        // Only one version/hash plan is run by the owning view model.
        directory.listFiles()?.filter { it != destination }?.forEach { it.delete() }
        var last: Exception? = null
        for (url in release.urls) {
            currentCoroutineContext().ensureActive()
            val temporary = File(directory, destination.name + ".download")
            try {
                progress(UpdateDownloadState(UpdateDownloadPhase.Downloading))
                http.read(url) { connection ->
                    val declared = connection.contentLengthLong
                    require(declared < 0 || declared == release.size) { "安装包大小不符" }
                    connection.inputStream.use { input -> FileOutputStream(temporary).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L; var lastProgress = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            total += count
                            require(total <= release.size) { "安装包大小不符" }
                            output.write(buffer, 0, count)
                            val now = System.nanoTime()
                            if (now - lastProgress >= 300_000_000) {
                                progress(UpdateDownloadState(UpdateDownloadPhase.Downloading,
                                    if (declared > 0) (total * 100 / declared).toInt().coerceAtMost(99) else null))
                                lastProgress = now
                            }
                        }
                        require(total == release.size) { "安装包下载不完整" }
                        output.fd.sync()
                    } }
                }
                progress(UpdateDownloadState(UpdateDownloadPhase.Verifying))
                verify(release, temporary)
                check(temporary.renameTo(destination)) { "无法保存安装包" }
                return destination
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { last = error }
              finally { temporary.delete() }
        }
        throw java.io.IOException("所有下载来源均失败，请重试", last)
    }

    /** Re-hash on every install and authorization return; never trust a historical flag. */
    suspend fun verify(release: UpdateRelease, file: File = apk(release)) = withContext(Dispatchers.IO) {
        try {
            require(release.minSdk <= android.os.Build.VERSION.SDK_INT) { "当前系统不支持此更新，请重新检查" }
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
                ?: error("无法读取安装包")
            val installed = context.packageManager.getPackageInfo(context.packageName, flags)
            require(candidate.packageName == context.packageName && candidate.packageName == release.packageName) { "安装包包名不匹配" }
            require(candidate.longVersionCode == release.versionCode.toLong() && candidate.longVersionCode > installed.longVersionCode) { "安装包版本不匹配" }
            val oldSigners = installed.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
            val newSigners = candidate.signingInfo?.apkContentsSigners?.map { sha256(it.toByteArray()) }?.toSet().orEmpty()
            require(oldSigners.isNotEmpty() && oldSigners == newSigners) { "安装包签名与当前应用不一致" }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (error: Exception) { file.delete(); throw error }
    }
    fun authorized() = context.packageManager.canRequestPackageInstalls()
    fun authorizationIntent() = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:${context.packageName}"))
    fun installIntent(release: UpdateRelease): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(contentUri(apk(release)),
            "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
