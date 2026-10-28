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
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int) = type
        }
        val model = MyViewModel(app, AccountRepository(ykt, vault, profileService = profiles))
        store.put("mine", model)
        model.accountChanged(vault.username); model.refresh()
        await { entered && summaries == 1 }
        val readsBeforeSwitch = summaries
        vault.username = "fake-owner-b"
        model.accountChanged(vault.username)
        assertNull(model.state.value.profile)
        assertNull(model.state.value.avatar)
        assertNull(model.state.value.summary)
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(model.state.value.profile)
        assertFalse(model.state.value.loading)
        assertEquals(readsBeforeSwitch, summaries)
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

    @Test fun returningAfterOfflineFailureRetriesImmediatelyWithoutTreatingErrorsAsFreshCache() {
        var offline = true
        var profileReads = 0
        var summaryEntered = false
        val profiles = object : ProfileService {
            override suspend fun profile(token: String): PersonalProfile {
                profileReads++
                if (offline) throw NetworkFailure()
                return PersonalProfile("示例姓名", "fake-id", "示例部门", "示例专业", null)
            }
            override suspend fun avatar(path: String): ByteArray? = null
            override suspend fun homeIdentity(token: String, sno: String): HomeCardIdentity? = null
        }
        val ykt = object : YktService by YktApi({ error("No live request") }) {
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
                if (offline) throw NetworkFailure()
                summaryEntered = true; gate.await(); return BillSummary(100, 200)
            }
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int): Int {
                if (offline) throw NetworkFailure()
                return type
            }
        }
        val model = MyViewModel(app, AccountRepository(ykt, vault, profileService = profiles))
        store.put("mine", model)
        model.accountChanged(vault.username); model.onActivate()
        await { model.state.value.loaded && !model.state.value.loading }
        assertNotNull(model.state.value.profileError)
        assertNotNull(model.state.value.summaryError)
        offline = false; model.onActivate()
        await { summaryEntered && model.state.value.profile != null }
        assertEquals(2, profileReads)
        assertNull(model.state.value.profileError)
        assertNull(model.state.value.summaryError)
        assertNull(model.state.value.countsError)
        assertTrue(model.state.value.loading)
        gate.complete(Unit)
        await { !model.state.value.loading }
        assertNull(model.state.value.summaryError)
    }
    @Test fun firstReadPublishesIdentityBeforeTheSlowMonthlySummary() {
        var summaryEntered = false
        val profiles = object : ProfileService {
            override suspend fun profile(token: String) = PersonalProfile("示例姓名", "fake-id", "示例部门", "示例专业", null)
            override suspend fun avatar(path: String): ByteArray? = null
        }
        val ykt = object : YktService by YktApi({ error("No live request") }) {
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
                summaryEntered = true; gate.await(); return BillSummary(100, 200)
            }
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int) = type
        }
        val model = MyViewModel(app, AccountRepository(ykt, vault, profileService = profiles))
        store.put("mine", model)
        model.accountChanged(vault.username); model.refresh()
        await { summaryEntered && model.state.value.profileLoaded && model.state.value.countsLoaded }
        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.loaded)
        assertEquals("示例姓名", model.state.value.profile?.name)
        assertFalse(model.state.value.summaryLoaded)
        assertEquals(3, model.state.value.counts!!.income + model.state.value.counts!!.expenses)
        assertNull(model.state.value.summary)
        gate.complete(Unit)
        await { !model.state.value.loading }
        assertTrue(model.state.value.loaded)
        assertEquals("示例姓名", model.state.value.profile?.name)
        assertEquals(100L, model.state.value.summary?.incomeCents)
        assertEquals(3, model.state.value.counts!!.income + model.state.value.counts!!.expenses)
    }

    @Test fun refreshingReplacesTheMonthlyErrorWithoutWaitingForTheProfile() {
        var refreshing = false
        var profileEntered = false
        val profiles = object : ProfileService {
            override suspend fun profile(token: String): PersonalProfile {
                if (refreshing) { profileEntered = true; gate.await() }
                return PersonalProfile("示例姓名", "fake-id", "示例部门", "", null)
            }
            override suspend fun avatar(path: String): ByteArray? = null
        }
        val ykt = object : YktService by YktApi({ error("No live request") }) {
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
                if (!refreshing) throw NetworkFailure()
                return BillSummary(100, 200)
            }
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int) = type
        }
        val model = MyViewModel(app, AccountRepository(ykt, vault, profileService = profiles))
        store.put("mine", model)
        model.accountChanged(vault.username); model.refresh()
        await { !model.state.value.loading }
        val previous = model.state.value
        assertNotNull(previous.summaryError)
        refreshing = true; model.refresh()
        await { profileEntered && model.state.value.summary != null }
        assertTrue(model.state.value.loaded)
        assertSame(previous.profile, model.state.value.profile)
        assertNull(model.state.value.summaryError)
        assertTrue(model.state.value.loading)
        gate.complete(Unit)
        await { !model.state.value.loading }
        assertNull(model.state.value.summaryError)
        assertNotNull(model.state.value.summary)
    }

    @Test fun profileSummaryAndBothCountsBeginWithoutWaitingForEachOther() {
        var profileEntered = false
        var summaryEntered = false
        val directions = mutableSetOf<Int>()
        val profiles = object : ProfileService {
            override suspend fun profile(token: String): PersonalProfile {
                profileEntered = true; gate.await()
                return PersonalProfile("示例姓名", "fake-id", "示例部门", "", null)
            }
            override suspend fun avatar(path: String): ByteArray? = null
        }
        val ykt = object : YktService by YktApi({ error("No live request") }) {
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
                summaryEntered = true; gate.await(); return BillSummary(100, 200)
            }
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int): Int {
                directions += type; gate.await(); return type
            }
        }
        val model = MyViewModel(app, AccountRepository(ykt, vault, profileService = profiles))
        store.put("mine", model)
        model.accountChanged(vault.username); model.refresh()
        await { profileEntered && summaryEntered && directions.size == 2 }
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.profile)
        gate.complete(Unit)
        await { !model.state.value.loading }
        assertEquals("示例姓名", model.state.value.profile?.name)
        assertEquals(100L, model.state.value.summary?.incomeCents)
        assertEquals(3, model.state.value.counts!!.income + model.state.value.counts!!.expenses)
    }

    @Test fun avatarAndCardIdentityBeginTogetherAfterProfileProvidesTheirInputs() {
        var avatarEntered = false
        var identityEntered = false
        val profiles = object : ProfileService {
            override suspend fun profile(token: String) = PersonalProfile("示例姓名", "fake-id", "示例部门", "", "/minio/demo.png")
            override suspend fun avatar(path: String): ByteArray {
                avatarEntered = true; gate.await(); return byteArrayOf(1)
            }
            override suspend fun homeIdentity(token: String, sno: String): HomeCardIdentity {
                assertEquals("fake-id", sno)
                identityEntered = true; gate.await(); return HomeCardIdentity("示例姓名", sno, "示例书院", "示例专业")
            }
        }
        val ykt = object : YktService by YktApi({ error("No live request") }) {
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String) = BillSummary(100, 200)
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int) = type
        }
        val model = MyViewModel(app, AccountRepository(ykt, vault, profileService = profiles))
        store.put("mine", model)
        model.accountChanged(vault.username); model.refresh()
        await { avatarEntered && identityEntered }
        assertEquals("示例姓名", model.state.value.profile?.name)
        assertTrue(model.state.value.profileLoaded)
        assertFalse(model.state.value.homeIdentityLoaded)
        assertNull(model.state.value.avatar)
        assertTrue(model.state.value.loading)
        gate.complete(Unit)
        await { !model.state.value.loading }
        assertArrayEquals(byteArrayOf(1), model.state.value.avatar)
        assertEquals("示例书院", model.state.value.homeIdentity?.thirdLevelDepartment)
    }

    @Test fun failedCountDoesNotCancelThePendingMonthlySummary() {
        var summaryEntered = false
        var countFailed = false
        val profiles = object : ProfileService {
            override suspend fun profile(token: String) = PersonalProfile("示例姓名", "fake-id", "示例部门", "", null)
            override suspend fun avatar(path: String): ByteArray? = null
        }
        val ykt = object : YktService by YktApi({ error("No live request") }) {
            override suspend fun billSummary(token: String, timeFrom: String, timeTo: String): BillSummary {
                summaryEntered = true
                withContext(NonCancellable) { gate.await() }
                return BillSummary(100, 200)
            }
            override suspend fun billCount(token: String, timeFrom: String, timeTo: String, type: Int): Int {
                countFailed = true; throw NetworkFailure()
            }
        }
        val model = MyViewModel(app, AccountRepository(ykt, vault, profileService = profiles))
        store.put("mine", model)
        model.accountChanged(vault.username); model.refresh()
        await { summaryEntered && countFailed }
        gate.complete(Unit)
        await { !model.state.value.loading }
        assertEquals(100L, model.state.value.summary?.incomeCents)
        assertNull(model.state.value.counts)
        assertNotNull(model.state.value.countsError)
        assertNull(model.state.value.summaryError)
        assertEquals("示例姓名", model.state.value.profile?.name)
    }

    @Test fun lateDormReadCannotRestorePreviousAccountRoomAfterLogout() {
        var entered = false
        val fees = object : FeeService by FeeApi({ error("No live request") }) {
            override suspend fun feeConfiguration(token: String, project: FeeProject): FeeConfiguration {
                entered = true
                withContext(NonCancellable) { gate.await() }
                return FeeConfiguration(project, RechargeConfiguration(project.id, "", listOf(100), 1, 10000, true, false))
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
