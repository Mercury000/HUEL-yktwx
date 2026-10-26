package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalDataViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = ViewModelStore()
    private val gate = CompletableDeferred<Unit>()
    private val vault = object : Vault {
        var username = "fake-owner-a"
        override fun credentials() = Credentials(username, "fake-password")
        override fun session() = Session("fake-token", Long.MAX_VALUE)
        override fun saveCredentials(value: Credentials) = Unit
        override fun saveSession(value: Session) = Unit
        override fun clearSession() = Unit
        override fun clear() = Unit
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for personal data")
    }
    @After fun cleanup() { gate.complete(Unit); store.clear(); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun accountSwitchClearsProfileAvatarAndMonthlySummaryAndRejectsLateOldResponse() {
        var entered = false
        var summaries = 0
        val profiles = object : ProfileService {
            override suspend fun profile(token: String): PersonalProfile {
                entered = true
                withContext(NonCancellable) { gate.await() }
                return PersonalProfile("旧账户", "old-id", "旧部门", "", "/minio/demo.png")
            }
            override suspend fun avatar(path: String) = byteArrayOf(1)
        }
        val ykt = object : YktService by YktApi({ error("No live request") }) {
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary { summaries++; return BillSummary(100, 200) }
        }
        val model = MyViewModel(app, AccountRepository(ykt, vault, profileService = profiles))
        store.put("mine", model)
        model.accountChanged(vault.username); model.refresh()
        await { entered }
        vault.username = "fake-owner-b"
        model.accountChanged(vault.username)
        assertNull(model.state.value.profile)
        assertNull(model.state.value.avatar)
        assertNull(model.state.value.summary)
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(model.state.value.profile)
        assertFalse(model.state.value.loading)
        assertEquals(0, summaries)
    }
    @Test fun monthlyFailureKeepsOriginalProfileAndNeverInventsIncomeOrExpenses() {
        val profiles = object : ProfileService {
            override suspend fun profile(token: String) = PersonalProfile("示例姓名", "fake-id", "示例部门", "", null)
            override suspend fun avatar(path: String): ByteArray? = null
        }
        val ykt = object : YktService by YktApi({ error("No live request") }) {
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary = throw NetworkFailure()
        }
        val model = MyViewModel(app, AccountRepository(ykt, vault, profileService = profiles))
        store.put("mine", model)
        model.accountChanged(vault.username); model.refresh()
        await { !model.state.value.loading }
        assertEquals("示例部门", model.state.value.profile?.departmentName)
        assertEquals("", model.state.value.profile?.professionName)
        assertNull(model.state.value.summary)
        assertNotNull(model.state.value.summaryError)
        assertNull(model.state.value.profileError)
    }
    @Test fun lateDormReadCannotRestorePreviousAccountRoomAfterLogout() {
        var entered = false
        val fees = object : FeeService by FeeApi({ error("No live request") }) {
            override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
                entered = true
                withContext(NonCancellable) { gate.await() }
                return FeeConfiguration(project, "示例项目", RechargeConfiguration(project.id, "", "", 0, listOf(100), 1, 10000, true, false))
            }
        }
        val model = HallViewModel(app, AccountRepository(YktApi(), vault, feeService = fees))
        store.put("dorm", model)
        model.accountChanged(vault.username); model.refresh()
        await { entered }
        model.accountChanged("")
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(model.state.value.rooms.isEmpty())
        assertFalse(model.state.value.loaded)
        assertFalse(model.state.value.loading)
    }
}
