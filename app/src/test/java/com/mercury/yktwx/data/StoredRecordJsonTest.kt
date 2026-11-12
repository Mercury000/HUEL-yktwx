package com.mercury.yktwx.data

import org.junit.Assert.*
import org.junit.Test

class StoredRecordJsonTest {
    @Test fun existingCredentialsAndSessionReadWithoutAnExtraSerializerAndRewriteCurrentVersion() {
        val credentials = StoredRecordJson.decode<StoredCredentials>(parseObject(
            """{"username":"fixture-user","password":"fixture-password"}"""))
        val session = StoredRecordJson.decode<StoredSession>(parseObject(
            """{"token":"fixture-token","expires":1795000000000}"""))
        assertEquals("fixture-user", credentials.username)
        assertEquals("fixture-password", credentials.password)
        assertEquals(1795000000000, session.expires)
        assertEquals(credentials, StoredRecordJson.decode<StoredCredentials>(StoredRecordJson.encode(credentials)))
        assertEquals(session, StoredRecordJson.decode<StoredSession>(StoredRecordJson.encode(session)))
        assertEquals(2L, StoredRecordJson.encode(credentials).strictLongValue("schemaVersion"))
        assertFalse(credentials.toString().contains("fixture-password"))
        assertFalse(session.toString().contains("fixture-token"))
    }

    @Test fun unsupportedOrMistypedVersionsAreRejectedAtTheSharedBoundary() {
        for (version in listOf("0", "1", "3", "2.0", "\"2\"", "true", "null")) {
            assertThrows(IllegalArgumentException::class.java) {
                StoredRecordJson.decode<StoredSession>(parseObject("""{"schemaVersion":$version,"token":"token","expires":1}"""))
            }
        }
    }

    @Test fun oldSingleRecordsHaveStableIdentityAndCurrentMissingIdentityIsRejected() {
        val old = parseObject("""{"owner":"owner","skuId":"water","orderNo":"order/1"}""")
        assertEquals("legacy-water-order/1-water", StoredRecordJson.singleId(old, "water"))
        assertEquals(StoredRecordJson.single(old, "water"), StoredRecordJson.single(old, "water"))
        assertThrows(IllegalArgumentException::class.java) {
            StoredRecordJson.single(old.withField("schemaVersion", 2), "water")
        }
    }

    @Test fun emptyCurrentListNeverResurrectsAnOldSingleRecord() {
        val current = parseObject("""{"owner":"owner","schemaVersion":2,"sessions":[]}""")
        var oldRead = false
        assertEquals(current, StoredRecordJson.listSource(current, { oldRead = true; error("Old record must be ignored") }, "water"))
        assertFalse(oldRead)
        val old = parseObject("""{"owner":"owner","skuId":"water","phase":"Starting","startSent":true}""")
        val restored = StoredRecordJson.listSource(null, { old }, "water")!!
        assertEquals("legacy-water--water", restored.arrayValue("sessions").objectValue(0).stringValue("sessionId"))
        assertEquals(true, restored.arrayValue("sessions").objectValue(0).booleanValue("startSent"))
    }
}
