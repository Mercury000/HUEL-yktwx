package com.mercury.yktwx

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.mercury.yktwx.data.*
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.LocalReadOffline
import com.mercury.yktwx.ui.pages.BillPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BillOfflineTypeSwitchUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val folder = TemporaryFolder()

    @Test fun firstOfflineSwitchWithBothTypesCachedKeepsNoticeDuringTheSlide() =
        firstOfflineSwitch(cacheReadFrames = 4)

    @Test fun firstOfflineSwitchWithBothTypesCachedKeepsNoticeAfterTheSlide() =
        firstOfflineSwitch(cacheReadFrames = 35)

    @Test fun rapidOfflineTypeReversalsWithBothTypesCachedKeepTheNotice() =
        firstOfflineSwitch(cacheReadFrames = 4, reverse = true)

    @Test fun uncachedOfflineIncomeShowsOneFirstReadFailureWithoutClaimingOldData() =
        firstOfflineSwitch(cacheReadFrames = 4, incomeCached = false)

    private fun firstOfflineSwitch(cacheReadFrames: Int, reverse: Boolean = false, incomeCached: Boolean = true) {
        val holdRead = AtomicBoolean(false)
        val networkReads = AtomicInteger()
        val releaseRead = CountDownLatch(1)
        val cache = DisplayCache(folder.root, { bytes, _ -> bytes }, { bytes, _ ->
            if (holdRead.get()) check(releaseRead.await(30, TimeUnit.SECONDS))
            bytes
        })
        val vault = object : Vault {
            override fun credentials() = Credentials("fictional-user", "fictional-password")
            override fun session() = Session("fake-token", Long.MAX_VALUE)
            override fun saveCredentials(value: Credentials) = Unit
            override fun saveSession(value: Session) = Unit
            override fun clearSession() = Unit
            override fun clear() = Unit
        }
        val service = object : YktService by FakeAccountService() {
            override suspend fun billTrend(token: String, type: Int, filter: BillDateFilter): List<BillDailyPoint> {
                networkReads.incrementAndGet(); throw NetworkFailure()
            }
            override suspend fun billCategories(token: String, type: Int, timeFrom: String, timeTo: String): List<BillCategory> {
                networkReads.incrementAndGet(); throw NetworkFailure()
            }
            override suspend fun billRanking(token: String, type: Int, timeFrom: String, timeTo: String, limit: Int): List<BillRecord> {
                networkReads.incrementAndGet(); throw NetworkFailure()
            }
        }
        val repository = AccountRepository(service, vault, displayCache = cache)
        val period = BillDateFilter.currentMonth()
        runBlocking {
            repository.username()
            for (type in 1..2) {
                if (type == 1 && !incomeCached) continue
                val stats = PreviewData.billStats(period, type)
                repository.saveDisplay("fictional-user", DisplayKeys.stats(period, type, "daily"), stats.daily, DisplayCodecs.Daily)
                repository.saveDisplay("fictional-user", DisplayKeys.stats(period, type, "categories"), stats.categories, DisplayCodecs.Categories)
                repository.saveDisplay("fictional-user", DisplayKeys.stats(period, type, "ranking"), stats.ranking, DisplayCodecs.Records)
            }
        }
        val model = BillViewModel(compose.activity.application, repository)
        val store = ViewModelStore().apply { put("bill", model) }
        val settings = ThemeSettings(compose.activity.application)
        fun frame() { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        try {
            model.accountChanged("fictional-user")
            model.setPageVisible(true, online = false)
            model.openStats()
            compose.setContent {
                val state by model.state.collectAsState()
                CompositionLocalProvider(LocalInspectionMode provides true, LocalReadOffline provides true) {
                    AppTheme {
                        BillPage(state, settings, {}, model::refresh, model::loadMore, model::selectDate,
                            model::applyFilters, model::search, model::openStats, model::openDetail,
                            onShowDetails = model::showDetails, onStatsTypeChange = model::selectStatsType,
                            showBack = false)
                    }
                }
            }
            compose.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                model.state.value.stats.loaded && !model.state.value.stats.loading
            }
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
            repeat(20) { frame() }
            val notice = compose.onNodeWithTag("page-network-error")
            val noticeImage = notice.captureToImage().asAndroidBitmap()
            val noticeBounds = notice.fetchSemanticsNode().boundsInRoot
            val tabBounds = compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot
            fun assertStable(index: Int) {
                assertEquals("Existing cached offline notice must stay still at frame $index",
                    tabBounds, compose.onNodeWithTag("bill-mode-tabs").fetchSemanticsNode().boundsInRoot)
                assertEquals(noticeBounds, notice.fetchSemanticsNode().boundsInRoot)
                assertTrue(noticeImage.sameAs(notice.captureToImage().asAndroidBitmap()))
            }
            holdRead.set(true)
            compose.onNode(hasText("收入") and hasClickAction()).performClick()
            assertEquals("First income click must select income", 1, model.state.value.stats.type)
            repeat(cacheReadFrames) { index ->
                frame()
                assertStable(index)
            }
            if (reverse) {
                compose.onNode(hasText("支出") and hasClickAction()).performClick()
                assertEquals("Reverse expense click must select expense", 2, model.state.value.stats.type)
                // StateFlow delivery and the visible tab selection settle on separate frames.
                repeat(3) { index -> frame(); assertStable(index) }
                compose.onNode(hasText("收入") and hasClickAction()).performClick()
                assertEquals("Second income click must select income", 1, model.state.value.stats.type)
                repeat(35) { index -> frame(); assertStable(index) }
            }
            releaseRead.countDown()
            try {
                compose.waitUntil(5_000) {
                    shadowOf(Looper.getMainLooper()).idle()
                    model.state.value.stats.type == 1 && !model.state.value.stats.restoringDisplay && !model.state.value.stats.loading
                }
            } catch (error: ComposeTimeoutException) {
                val stats = model.state.value.stats
                fail("Income did not settle: type=${stats.type}, loaded=${stats.loaded}, loading=${stats.loading}, restoring=${stats.restoringDisplay}")
            }
            repeat(if (incomeCached) 25 else 45) { index ->
                frame()
                if (incomeCached) {
                    compose.onAllNodesWithTag("page-network-error").assertCountEquals(1)
                    assertStable(index)
                }
            }
            if (incomeCached) {
                compose.onNodeWithTag("bill-stats-amount").assertIsDisplayed()
                compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
            } else {
                compose.onNodeWithTag("page-network-error").assertDoesNotExist()
                compose.onNodeWithTag("read-unavailable").assertIsDisplayed()
                compose.onAllNodesWithText("网络连接失败，请下拉刷新").assertCountEquals(1)
                compose.onAllNodesWithText("显示上次数据", substring = true).assertCountEquals(0)
                compose.onNodeWithTag("bill-stats-amount").assertDoesNotExist()
            }
            assertFalse(model.state.value.stats.restoringDisplay)
            assertEquals("Offline type changes must not start network reads", 0, networkReads.get())
            compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
        } finally {
            releaseRead.countDown()
            store.clear()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
