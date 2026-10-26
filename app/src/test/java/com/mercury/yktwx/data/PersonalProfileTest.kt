package com.mercury.yktwx.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class PersonalProfileTest {
    private class FakeHttps(url: URL, val bytes: ByteArray, private val type: String = "application/json", private val status: Int = 200) : HttpsURLConnection(url) {
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentType() = type
        override fun getInputStream() = ByteArrayInputStream(bytes)
        override fun getErrorStream() = ByteArrayInputStream(bytes)
        override fun getCipherSuite() = "TLS_FAKE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    @Test fun profileUsesAuthenticatedUserEndpointAndPreservesDepartmentAndProfession() = runBlocking {
        val data = JSONObject().put("name", "示例姓名").put("sno", "fake-id")
            .put("departmentName", "示例部门").put("professionName", "示例专业").put("avatar", "/minio/demo.png")
            .put("mobile", "13000000000")
        val body = JSONObject().put("code", 200).put("success", true).put("data", data).toString()
        lateinit var request: FakeHttps
        val api = ProfileApi { url -> FakeHttps(url, body.toByteArray()).also { request = it } }
        val profile = api.profile("fake-token")
        assertEquals("/berserker-base/user", request.url.path)
        assertEquals("synAccessSource=h5", request.url.query)
        assertEquals("bearer fake-token", request.getRequestProperty("synjones-auth"))
        assertNull(request.getRequestProperty("Cookie"))
        assertEquals("示例部门", profile.departmentName)
        assertEquals("示例专业", profile.professionName)
        assertEquals("/minio/demo.png", profile.avatarPath)
        assertEquals("13000000000", profile.mobile)
        assertFalse(profile.toString().contains("fake-id"))
        assertFalse(profile.toString().contains("13000000000"))
        assertTrue(request.closed)
    }
    @Test fun missingProfessionStaysEmptyAndNeverFallsBackToDepartment() {
        val profile = ProfileApi.parse(JSONObject().put("departmentName", "示例部门").put("professionName", JSONObject.NULL).put("sno", 123))
        assertEquals("示例部门", profile.departmentName)
        assertEquals("", profile.professionName)
        assertEquals("", profile.sno)
    }
    private fun homeModel(sno: String = "fake-id", college: String = "示例书院", profession: String = "示例专业"): String {
        val model = org.json.JSONArray()
        listOf("name" to "示例姓名", "sno" to sno, "thirdLevelDepartment" to college, "fourthLevelDepartment" to profession).forEach { (code, value) ->
            model.put(JSONObject().put("type", "text").put("name", "textInfoCom")
                .put("prop", JSONObject().put("code", code).put("value", value)))
        }
        model.put(JSONObject().put("type", "img").put("prop", JSONObject().put("imageUrl", "https://example.com/unused.png")))
        return model.toString()
    }
    @Test fun homeIdentityReadsResolvedInformationWithoutFetchingOrRenderingCardImages() = runBlocking {
        val data = JSONObject().put("fake-account", JSONObject().put("model", homeModel()))
        val body = JSONObject().put("code", 200).put("success", true).put("data", data).toString()
        lateinit var request: FakeHttps
        val api = ProfileApi { url -> FakeHttps(url, body.toByteArray()).also { request = it } }
        val identity = api.homeIdentity("fake-token", "fake-id")!!
        assertEquals("/berserker-app/cardStyle", request.url.path)
        assertEquals("bearer fake-token", request.getRequestProperty("synjones-auth"))
        assertNull(request.getRequestProperty("Cookie"))
        assertEquals("示例书院", identity.thirdLevelDepartment)
        assertEquals("示例专业", identity.fourthLevelDepartment)
        assertEquals("fake-id", identity.sno)
        assertFalse(identity.toString().contains("fake-id"))
    }
    @Test fun homeIdentityRejectsOtherOwnersAndConflictingCards() {
        val other = JSONObject().put("other", JSONObject().put("model", homeModel(sno = "other-id")))
        assertNull(ProfileApi.parseHomeIdentity(other, "fake-id"))
        other.put("a", JSONObject().put("model", homeModel()))
        other.put("b", JSONObject().put("model", homeModel(college = "另一书院")))
        assertNull(ProfileApi.parseHomeIdentity(other, "fake-id"))
        other.put("b", JSONObject().put("model", homeModel()))
        assertEquals("示例书院", ProfileApi.parseHomeIdentity(other, "fake-id")!!.thirdLevelDepartment)
    }
    @Test fun avatarOnlyAcceptsSchoolMinioPaths() {
        assertEquals("/minio/avatar.png", ProfileApi.avatarPath("https://yktwx2.huel.edu.cn/minio/avatar.png"))
        listOf("https://example.com/minio/avatar.png", "//example.com/minio/avatar.png", "http://yktwx2.huel.edu.cn/minio/avatar.png",
            "https://yktwx2.huel.edu.cn:8443/minio/avatar.png", "https://user@yktwx2.huel.edu.cn/minio/avatar.png",
            "/minio/../secret", "/minio/%2e%2e/secret", "/minio/a.png?token=private", "/minio/a.png#private", "/user/profile", "").forEach {
            assertNull("Rejected avatar: $it", ProfileApi.avatarPath(it))
        }
    }
    @Test fun avatarIsPublicInMemoryAndRejectsRedirectsOrOversizedResponses() = runBlocking {
        val calls = mutableListOf<FakeHttps>()
        var bytes = byteArrayOf(1, 2, 3)
        var status = 200
        val api = ProfileApi { url -> FakeHttps(url, bytes, "image/png", status).also(calls::add) }
        assertArrayEquals(bytes, api.avatar("/minio/demo.png"))
        val image = calls.single()
        assertNull(image.getRequestProperty("synjones-auth"))
        assertNull(image.getRequestProperty("Authorization"))
        assertNull(image.getRequestProperty("Cookie"))
        assertFalse(image.instanceFollowRedirects)
        assertFalse(image.useCaches)
        assertEquals("no-store", image.getRequestProperty("Cache-Control"))
        bytes = ByteArray(2_097_153)
        assertNull(api.avatar("/minio/demo.png"))
        status = 302
        assertNull(api.avatar("/minio/demo.png"))
        assertNull(api.avatar("https://example.com/minio/avatar.png"))
        assertEquals(3, calls.size)
        assertTrue(calls.all { it.closed })
    }
}
