package com.mercury.yktwx.data

import org.junit.Assert.*
import org.junit.Test

class KeyboardEncoderTest {
    @Test fun allSupportedCharactersMapInOrder() {
        val map = KeyboardEncoder.layouts.mapValues { (_, value) -> value.reversed() }
        val password = KeyboardEncoder.layouts.values.joinToString("")
        val expected = KeyboardEncoder.layouts.values.joinToString("") { it.reversed() } + "\$1\$fake-uuid"
        assertEquals(expected, KeyboardEncoder.encode(password, map, "fake-uuid"))
    }
    @Test fun invalidCharactersAreRejected() {
        for (value in listOf("", "含中文", "space here", "emoji😀")) {
            assertThrows(YktException::class.java) { KeyboardEncoder.validate(value) }
        }
    }
    @Test fun malformedMappingIsRejected() {
        assertThrows(YktException::class.java) { KeyboardEncoder.encode("1", emptyMap(), "fake-uuid") }
        assertThrows(YktException::class.java) { KeyboardEncoder.encode("1", KeyboardEncoder.layouts + ("numberKeyboard" to "01"), "fake-uuid") }
    }
    @Test fun unsafeUuidIsRejected() {
        for (uuid in listOf("", "has space", "bad\$uuid")) {
            assertThrows(YktException::class.java) { KeyboardEncoder.encode("1", KeyboardEncoder.layouts, uuid) }
        }
    }
    @Test fun sensitiveModelStringsAreRedacted() {
        assertFalse(Credentials("123", "secret-value").toString().contains("secret-value"))
        assertFalse(Session("secret-token", 1).toString().contains("secret-token"))
    }
    @Test fun displayIntervalDoesNotTreatExpiresAsOfflinePromise() {
        val account = PaymentAccount("0", "0", "0", "demo", 0)
        assertEquals(60L, PaymentBatch(emptyList(), 17280, 0, account).displayIntervalSeconds)
        assertEquals(10L, PaymentBatch(emptyList(), 10, 0, account).displayIntervalSeconds)
    }
}
