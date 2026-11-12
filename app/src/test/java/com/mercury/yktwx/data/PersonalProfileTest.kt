package com.mercury.yktwx.data

import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL

class PersonalProfileTest {
    private class FakeResponse(url: URL, val bytes: ByteArray, private val type: String = "application/json", private val status: Int = 200) : HttpFixture(url) {
        var closed = false
        var responseFailure: IOException? = null

        override fun close() { closed = true }

        override fun statusCode(): Int {
            responseFailure?.let { throw it }
            return status
        }
        override fun contentType() = type
        override fun bodyStream() = ByteArrayInputStream(bytes)
        override fun errorBodyStream() = ByteArrayInputStream(bytes)

    }

    @Test fun profileUsesAuthenticatedUserEndpointAndPreservesDepartmentAndProfession() = runBlocking {
        val data = jsonObjectOf("name" to ("示例姓名"), "sno" to ("fake-id"), "departmentName" to ("示例部门"), "professionName" to ("示例专业"), "avatar" to ("/minio/demo.png"), "mobile" to ("13000000000"))
        val body = jsonObjectOf("code" to (200), "success" to (true), "data" to (data)).toString()
        lateinit var request: FakeResponse
        val api = fixtureProfileApi { url -> FakeResponse(url, body.toByteArray()).also { request = it } }
        val profile = api.profile("fake-token")
        assertEquals("/berserker-base/user", request.url.path)
        assertEquals("synAccessSource=h5", request.url.query)
        assertEquals("bearer fake-token", request.request.header("synjones-auth"))
        assertNull(request.request.header("Cookie"))
        assertEquals("示例部门", profile.departmentName)
        assertEquals("示例专业", profile.professionName)
        assertEquals("/minio/demo.png", profile.avatarPath)
        assertEquals("13000000000", profile.mobile)
        assertFalse(profile.toString().contains("fake-id"))
        assertFalse(profile.toString().contains("13000000000"))
        assertTrue(request.closed)
    }
    @Test fun missingProfessionStaysEmptyAndNeverFallsBackToDepartment() {
        val profile = ProfileApi.parse(jsonObjectOf("departmentName" to ("示例部门"), "professionName" to (JsonNull), "sno" to (123)))
        assertEquals("示例部门", profile.departmentName)
        assertEquals("", profile.professionName)
        assertEquals("", profile.sno)
    }
    private fun homeModel(sno: String = "fake-id", college: String = "示例书院", profession: String = "示例专业"): String {
        val model = buildJsonArray {
            listOf("name" to "示例姓名", "sno" to sno, "thirdLevelDepartment" to college, "fourthLevelDepartment" to profession).forEach { (code, value) ->
                add(jsonObjectOf("type" to "text", "name" to "textInfoCom", "prop" to jsonObjectOf("code" to code, "value" to value)))
            }
            add(jsonObjectOf("type" to "img", "prop" to jsonObjectOf("imageUrl" to "https://example.com/unused.png")))
        }
        return model.toString()
    }
    @Test fun homeIdentityReadsResolvedInformationWithoutFetchingOrRenderingCardImages() = runBlocking {
        val data = jsonObjectOf("fake-account" to (jsonObjectOf("model" to (homeModel()))))
        val body = jsonObjectOf("code" to (200), "success" to (true), "data" to (data)).toString()
        lateinit var request: FakeResponse
        val api = fixtureProfileApi { url -> FakeResponse(url, body.toByteArray()).also { request = it } }
        val identity = api.homeIdentity("fake-token", "fake-id")!!
        assertEquals("/berserker-app/cardStyle", request.url.path)
        assertEquals("bearer fake-token", request.request.header("synjones-auth"))
        assertNull(request.request.header("Cookie"))
        assertEquals("示例书院", identity.thirdLevelDepartment)
        assertEquals("示例专业", identity.fourthLevelDepartment)
        assertEquals("fake-id", identity.sno)
        assertFalse(identity.toString().contains("fake-id"))
    }
    @Test fun homeIdentityRejectsOtherOwnersAndConflictingCards() {
        var other = jsonObjectOf("other" to (jsonObjectOf("model" to (homeModel(sno = "other-id")))))
        assertNull(ProfileApi.parseHomeIdentity(other, "fake-id"))
        other = other.withField("a", jsonObjectOf("model" to (homeModel())))
            .withField("b", jsonObjectOf("model" to (homeModel(college = "另一书院"))))
        assertNull(ProfileApi.parseHomeIdentity(other, "fake-id"))
        other = other.withField("b", jsonObjectOf("model" to (homeModel())))
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
    @Test fun avatarTransportFailureKeepsItsNetworkCategoryAndClosesTheConnection() = runBlocking {
        val connection = FakeResponse(URL("https://yktwx2.huel.edu.cn/minio/demo.png"), byteArrayOf(), "image/png")
        connection.responseFailure = IOException("服务暂时无法访问")
        val api = fixtureProfileApi { connection }
        val failure = runCatching { api.avatar("/minio/demo.png") }.exceptionOrNull()
        assertTrue(failure is NetworkFailure)
        assertEquals(ReadFailureKind.Network, failure!!.readFailureKind())
        assertTrue(connection.closed)
    }

    @Test fun avatarConnectionFailureKeepsItsNetworkCategory() = runBlocking {
        val api = fixtureProfileApi { throw IOException("无法建立连接") }
        val failure = runCatching { api.avatar("/minio/demo.png") }.exceptionOrNull()
        assertTrue(failure is NetworkFailure)
        assertEquals(ReadFailureKind.Network, failure!!.readFailureKind())
    }

    @Test fun avatarIsPublicInMemoryAndRejectsRedirectsOrOversizedResponses() = runBlocking {
        val calls = mutableListOf<FakeResponse>()
        var bytes = byteArrayOf(1, 2, 3)
        var status = 200
        val api = fixtureProfileApi { url -> FakeResponse(url, bytes, "image/png", status).also(calls::add) }
        assertArrayEquals(bytes, api.avatar("/minio/demo.png"))
        val image = calls.single()
        assertNull(image.request.header("synjones-auth"))
        assertNull(image.request.header("Authorization"))
        assertNull(image.request.header("Cookie"))
        assertFalse(testHttpClients.service.followRedirects)
        assertFalse((testHttpClients.service.cache != null))
        assertEquals("no-store", image.request.header("Cache-Control"))
        bytes = ByteArray(2_097_153)
        assertNull(api.avatar("/minio/demo.png"))
        status = 302
        assertNull(api.avatar("/minio/demo.png"))
        assertNull(api.avatar("https://example.com/minio/avatar.png"))
        assertEquals(3, calls.size)
        assertTrue(calls.all { it.closed })
    }
}
