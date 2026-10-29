package com.mercury.yktwx.data

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisplayCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun cache(limit: Long = 5 * 1024 * 1024) = DisplayCache(folder.root,
        seal = { bytes, aad -> Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key); updateAAD(aad); iv + doFinal(bytes)
        } },
        open = { bytes, aad -> Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(aad); doFinal(bytes.copyOfRange(12, bytes.size))
        } }, limitBytes = limit)

    @Test fun restartRestoresAnEncryptedSnapshotAndSeparatesAccountsAndQueries() {
        val account = PaymentAccount("card", "pay", "1", "private-name", 18650)
        cache().write("owner-a", DisplayKeys.Account, account, DisplayCodecs.Account)
        val stored = folder.root.listFiles()!!.single().readBytes()
        assertFalse(String(stored, StandardCharsets.ISO_8859_1).contains("private-name"))
        assertEquals(18650L, cache().read("owner-a", DisplayKeys.Account, DisplayCodecs.Account)!!.balanceCents)
        assertNull(cache().read("owner-b", DisplayKeys.Account, DisplayCodecs.Account))
        assertNull(cache().read("owner-a", "different-query", DisplayCodecs.Account))
    }

    @Test fun emptySuccessSurvivesRestartAndIsDistinctFromAnUnreadQuery() {
        cache().write("owner", "bills", BillPageData(emptyList(), 1, 0), DisplayCodecs.Page)
        val page = cache().read("owner", "bills", DisplayCodecs.Page)!!
        assertTrue(page.items.isEmpty())
        assertNull(cache().read("owner", "other-date", DisplayCodecs.Page))
    }

    @Test fun corruptionAndCiphertextSwappingAreDiscardedWithoutBreakingOtherData() {
        cache().write("a", "balance", BillSummary(1, 2), DisplayCodecs.Summary)
        val first = folder.root.listFiles()!!.single()
        cache().write("b", "balance", BillSummary(3, 4), DisplayCodecs.Summary)
        val second = folder.root.listFiles()!!.single { it != first }
        second.writeBytes(first.readBytes())
        assertNull(cache().read("b", "balance", DisplayCodecs.Summary))
        assertEquals(1L, cache().read("a", "balance", DisplayCodecs.Summary)!!.incomeCents)
        first.writeText("corrupt")
        assertNull(cache().read("a", "balance", DisplayCodecs.Summary))
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }

    @Test fun diskBudgetIsEnforcedAndClearingOneAccountKeepsTheOther() {
        val cache = cache(700)
        repeat(30) { cache.write("a", "query-$it", BillSummary(it.toLong(), 1), DisplayCodecs.Summary) }
        assertTrue(folder.root.listFiles()!!.sumOf { it.length() } <= 700)
        assertNotNull(cache.read("a", "query-29", DisplayCodecs.Summary))
        cache.write("b", "query", BillSummary(100, 1), DisplayCodecs.Summary)
        cache.clear("a")
        assertNull(cache.read("a", "query-29", DisplayCodecs.Summary))
        assertEquals(100L, cache.read("b", "query", DisplayCodecs.Summary)!!.incomeCents)
    }

    @Test fun restoredFeeQueriesContainDisplayValuesAndNoPaymentAuthority() {
        val query = FeeQuery(thirdParty = "payment-handle", display = listOf("剩余电量" to "32.5"), canPay = true)
        cache().write("a", "meter", query, DisplayCodecs.Query)
        val restored = cache().read("a", "meter", DisplayCodecs.Query)!!
        assertEquals(query.display, restored.display)
        assertFalse(restored.canPay)
        assertNull(restored.thirdParty)
    }
}
