package com.mercury.yktwx.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

class FeeApiTest {
    private class FakeHttps(url: URL, private val body: String) : HttpsURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getCipherSuite() = "TLS_FAKE"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }

    private fun query(vararg data: Pair<String, String>) = runBlocking {
        val body = JSONObject().put("code", 200).put("map", JSONObject().put("data", JSONObject().apply {
            data.forEach { (key, value) -> put(key, value) }
        }))
        FeeApi { url -> FakeHttps(url, body.toString()) }.feeQuery("fake-token", FeeProject.Zhengdong, 4, emptyMap(), true)
    }

    @Test fun detailTargetAddsNamedRoomInfo() {
        val target = JSONObject(query(
            "area" to "1", "buildingName" to "示例1号楼", "areaName" to "示例校区",
            "extdata" to "", "floorName" to "1层", "floor" to "demo-floor", "aid" to "demo-aid",
            "account" to "demo-account", "building" to "demo-building", "room" to "demo-room", "roomName" to "示例1-101空调",
        ).thirdParty)
        assertEquals("房间：示例校区 示例1号楼 1层 示例1-101空调", target.getString("myCustomInfo"))
    }

    @Test fun detailTargetAddsIdValueRoomInfo() {
        val target = JSONObject(query(
            "room_id" to "demo-room&10号楼1单元001水表", "allAmp" to "100.00",
            "xiaoqu_id" to "demo-campus&示例校区", "usedAmp" to "50.00",
            "loudong_id" to "demo-building&10号楼", "louceng_id" to "demo-floor&10号楼1单元",
        ).thirdParty)
        assertEquals("房间：示例校区 10号楼 10号楼1单元 10号楼1单元001水表", target.getString("myCustomInfo"))
    }

    @Test fun detailTargetKeepsServerRoomInfo() {
        val target = JSONObject(query("roomName" to "示例1-101空调", "myCustomInfo" to "服务端摘要").thirdParty)
        assertEquals("服务端摘要", target.getString("myCustomInfo"))
    }
}
