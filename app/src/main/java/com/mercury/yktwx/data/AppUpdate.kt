package com.mercury.yktwx.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

internal data class UpdateRequest(
    val packageName: String, val versionCode: Int, val sdkInt: Int,
    val channel: String = "stable",
)

internal data class UpdateRelease(
    val versionCode: Int, val versionName: String, val notes: String,
    val downloadPageUrl: String,
)

internal enum class UpdateCheckReason { UpdateAvailable, CurrentOrNewer, NoPublishedRelease, NoCompatibleRelease }
internal data class UpdateCheckResult(val reason: UpdateCheckReason, val release: UpdateRelease? = null)

/** Public release metadata uses its own transport and never carries campus credentials. */
internal class AppUpdateApi(
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) {
    suspend fun check(request: UpdateRequest): UpdateCheckResult = withContext(Dispatchers.IO) {
        val connection = openConnection(checkUrl(request))
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            check(connection.responseCode == 200) { "更新服务暂时不可用" }
            val bytes = connection.inputStream.use { it.readNBytes(262_145) }
            require(bytes.size <= 262_144) { "更新信息过大" }
            parse(String(bytes, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val BASE_URL = "https://cxt.303889.xyz"

        internal fun checkUrl(request: UpdateRequest): URL {
            val params = linkedMapOf("packageName" to request.packageName,
                "versionCode" to request.versionCode.toString(), "sdkInt" to request.sdkInt.toString(),
                "channel" to request.channel)
            return URI(BASE_URL + "/v1/app/check?" + params.entries.joinToString("&") {
                URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8)
            }).toURL()
        }

        internal fun parse(body: String): UpdateCheckResult {
            val json = JSONObject(body)
            require(json.getInt("schemaVersion") == 1)
            val available = json.getBoolean("updateAvailable")
            val reason = when (json.getString("reason")) {
                "updateAvailable" -> UpdateCheckReason.UpdateAvailable
                "currentOrNewer" -> UpdateCheckReason.CurrentOrNewer
                "noPublishedRelease" -> UpdateCheckReason.NoPublishedRelease
                "noCompatibleRelease" -> UpdateCheckReason.NoCompatibleRelease
                else -> error("无法识别更新信息")
            }
            require(available == (reason == UpdateCheckReason.UpdateAvailable))
            require(json.has("latest"))
            if (!available) return UpdateCheckResult(reason)

            val latest = json.getJSONObject("latest")
            return UpdateCheckResult(reason, UpdateRelease(latest.getInt("versionCode"),
                latest.getString("versionName"), latest.getString("notes"),
                latest.getString("downloadPageUrl")))
        }
    }
}
