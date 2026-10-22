package com.mercury.yktwx.data

import org.junit.Assert.*
import org.junit.Test

class NumberKeyboardEncoderTest {
    @Test fun dollarAtEveryMappingPositionIsLegitimateAndPreservedVerbatim() {
        for (index in 0..9) {
            val mapping = "abcdefghij".toCharArray().apply { this[index] = '$' }.concatToString()
            val password = index.toString().repeat(6)
            assertEquals("1\$1\$" + "\$".repeat(6) + "\$1\$fake-uuid", NumberKeyboardEncoder.encode(password, mapping, "fake-uuid"))
        }
    }
    @Test fun frameLikeMappedPasswordIsNotEscapedOrStripped() {
        val mapping = "a\$1defghij"
        assertEquals("1\$1\$\$1\$1\$1\$1\$fake-uuid", NumberKeyboardEncoder.encode("121212", mapping, "fake-uuid"))
    }
    @Test fun malformedMappingsRemainRejected() {
        for (mapping in listOf("", "abc", "abcdefghijk", "aaaaaaaaaa", "abcdefghi ", "abcdefghi\n", "abcdefghi\u007f", "abcdefghi中")) {
            assertThrows(YktException::class.java) { NumberKeyboardEncoder.encode("012345", mapping, "fake-uuid") }
        }
    }
    @Test fun passwordAndUuidValidationRemainStrict() {
        for (password in listOf("", "12345", "1234567", "12345a", "１２３４５６")) {
            assertThrows(YktException::class.java) { NumberKeyboardEncoder.encode(password, "abcdefghij", "fake-uuid") }
        }
        for (uuid in listOf("", " ", "fake id", "bad\$id", "fake\n")) {
            assertThrows(YktException::class.java) { NumberKeyboardEncoder.encode("012345", "a\$cdefghij", uuid) }
        }
    }
}
