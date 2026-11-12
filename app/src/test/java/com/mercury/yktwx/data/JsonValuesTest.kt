package com.mercury.yktwx.data

import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test

class JsonValuesTest {
    @Test fun existingIdentityEncodingKeepsSlashesControlCharactersAndMissingFiltersStable() {
        assertEquals("""["account\/1","path\\name","line\nnext",null,100]""",
            stableIdentityArray(listOf("account/1", "path\\name", "line\nnext", null, 100L)))
        assertEquals("""avatar:["sno","\/minio\/avatar.png"]""", DisplayKeys.avatar("sno", "/minio/avatar.png"))
    }

    @Test fun serviceNumbersRemainDistinctFromQuotedNumbersAndBooleans() {
        val response = parseObject("""{"number":200,"quoted":"200","decimal":200.0,"flag":true,"nil":null}""")
        assertEquals(200, response.scalarOrElement("number"))
        assertEquals("200", response.scalarOrElement("quoted"))
        assertEquals(200.0, response.scalarOrElement("decimal"))
        assertEquals(true, response.scalarOrElement("flag"))
        assertEquals(200, response.intOrDefault("quoted"))
        assertEquals(-1, response.intOrDefault("flag", -1))
        assertTrue(response.isNull("nil"))
        assertThrows(SerializationException::class.java) { parseObject("[]") }
        assertThrows(SerializationException::class.java) { parseObject("{broken") }
    }
}
