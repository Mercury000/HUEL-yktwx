package com.mercury.yktwx.data

import java.net.URI
import okhttp3.Call
import org.json.JSONObject

/** Keep only display fields. The full user response contains identity and contact data. */
class PersonalProfile(
    val name: String, val sno: String, val departmentName: String,
    val professionName: String, val avatarPath: String?, val mobile: String = "",
) {
    override fun toString() = "PersonalProfile([redacted])"
}

interface ProfileService {
    suspend fun profile(token: String): PersonalProfile
    suspend fun avatar(path: String): ByteArray?
    suspend fun homeIdentity(token: String, sno: String): HomeCardIdentity? = null
}

/** Resolved home-card information, separate from the official personal-detail fields. */
class HomeCardIdentity(val name: String, val sno: String, val thirdLevelDepartment: String, val fourthLevelDepartment: String) {
    override fun toString() = "HomeCardIdentity([redacted])"
}

class ProfileApi(private val client: Call.Factory = AppHttp.service,
    private val imageClient: Call.Factory = AppHttp.publicRead) : ProfileService {
    private val http = PaymentHttp(client)
    override suspend fun profile(token: String): PersonalProfile {
        val data = http.json(http.request("/berserker-base/user", token)).getJSONObject("data")
        return parse(data)
    }
    override suspend fun homeIdentity(token: String, sno: String): HomeCardIdentity? {
        val data = http.json(http.request("/berserker-app/cardStyle", token)).getJSONObject("data")
        return parseHomeIdentity(data, sno)
    }
    override suspend fun avatar(path: String): ByteArray? {
        val safe = avatarPath(path) ?: return null
        try {
            val request = AppHttp.request(RechargeProtocol.SCHOOL + safe).header("Accept", "image/*").build()
            return imageClient.readResponse(request) { response ->
                // The observed avatar is public on the school origin; never send credentials to an image URL.
                if (response.code != 200 || response.header("Content-Type")?.substringBefore(';') !in setOf("image/png", "image/jpeg", "image/webp")) return@readResponse null
                val bytes = response.readBytesAtMost(2_097_152)
                bytes.takeIf { it.size in 1..2_097_152 }
            }
        } catch (e: java.io.IOException) { throw NetworkFailure(cause = e) }
    }
    companion object {
        internal fun parseHomeIdentity(data: JSONObject, sno: String): HomeCardIdentity? {
            if (sno.isBlank()) return null
            val identities = data.keys().asSequence().mapNotNull { account ->
                val raw = data.optJSONObject(account)?.opt("model") as? String ?: return@mapNotNull null
                val model = runCatching { org.json.JSONArray(raw) }.getOrNull() ?: return@mapNotNull null
                val fields = linkedMapOf<String, String>()
                for (i in 0 until model.length()) {
                    val element = model.optJSONObject(i) ?: continue
                    if (element.optString("type") != "text") continue
                    val prop = element.optJSONObject("prop") ?: continue
                    val code = prop.opt("code") as? String ?: continue
                    if (code !in setOf("name", "sno", "thirdLevelDepartment", "fourthLevelDepartment")) continue
                    val value = prop.opt("value") as? String ?: continue
                    // Ambiguous configured fields must not pick an arbitrary value.
                    if (fields.containsKey(code)) return@mapNotNull null
                    fields[code] = value
                }
                if (fields["sno"] != sno) return@mapNotNull null
                HomeCardIdentity(fields["name"].orEmpty(), sno, fields["thirdLevelDepartment"].orEmpty(), fields["fourthLevelDepartment"].orEmpty())
            }.toList()
            val distinct = identities.distinctBy { listOf(it.name, it.sno, it.thirdLevelDepartment, it.fourthLevelDepartment) }
            return distinct.singleOrNull()
        }

        internal fun parse(data: JSONObject): PersonalProfile {
            fun text(key: String) = (data.opt(key) as? String).orEmpty()
            return PersonalProfile(text("name"), text("sno"), text("departmentName"), text("professionName"), avatarPath(text("avatar")), text("mobile"))
        }
        internal fun avatarPath(raw: String): String? = runCatching {
            if (raw.isBlank()) return null
            val base = URI(RechargeProtocol.SCHOOL)
            val uri = base.resolve(raw)
            if (uri.scheme != "https" || uri.host != base.host || uri.port !in setOf(-1, 443) || uri.userInfo != null || uri.fragment != null ||
                !uri.path.startsWith("/minio/") || uri.normalize().path != uri.path || "%" in uri.rawPath || uri.query != null) return null
            uri.rawPath
        }.getOrNull()
    }
}
