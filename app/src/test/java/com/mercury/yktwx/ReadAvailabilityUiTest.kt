package com.mercury.yktwx

import com.mercury.yktwx.ui.themeSettingsFixture
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.mercury.yktwx.data.FeeQuery
import com.mercury.yktwx.data.DormMeter
import com.mercury.yktwx.data.DormMeterKind
import com.mercury.yktwx.data.DormMeterSlot
import com.mercury.yktwx.data.DormRoom
import com.mercury.yktwx.data.FeeLevel
import com.mercury.yktwx.data.FeeProject
import com.mercury.yktwx.data.FeeRoomBinding
import com.mercury.yktwx.data.ReadFailureKind
import com.mercury.yktwx.preview.PreviewData
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.BillPage
import com.mercury.yktwx.ui.pages.HallPage
import com.mercury.yktwx.ui.pages.MyPage
import com.mercury.yktwx.ui.pages.PersonalProfilePage
import com.mercury.yktwx.ui.pages.AccountDetailPage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Text

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReadAvailabilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @Test fun cachedReadPagesShowOneOfflineNoticeOnEntryAndClearItAfterReconnect() {
        val settings = themeSettingsFixture(compose.activity.application)
        val hall = PreviewData.hallState()
        val bill = PreviewData.billState()
        val mine = PreviewData.myState()
        val payment = PreviewData.paymentState(false)
        var page by mutableIntStateOf(0)
        var offline by mutableStateOf(true)
        var reading by mutableStateOf(false)
        compose.setContent {
            AppTheme {
                CompositionLocalProvider(LocalInspectionMode provides true, LocalReadOffline provides offline) {
                    key(page) {
                        when (page) {
                            0 -> HallPage(settings, 0.dp, dorm = hall, isActive = true)
                            1, 2 -> BillPage(bill.copy(statsVisible = page == 2), settings,
                                {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, showBack = false)
                            3 -> MyPage(mine, payment, settings, 0.dp, true, {}, {}, {}, {}, {},
                                false, {}, false, false, {}, {})
                            4 -> PersonalProfilePage(mine.copy(loading = reading), settings, {}, {})
                            5 -> AccountDetailPage(payment.copy(accountLoading = reading), settings, {}, {}, onRefresh = {})
                        }
                    }
                }
            }
        }
        for (index in 0..5) {
            val contentTag = when (index) {
                0 -> "hall-utility-card"
                1 -> "bill-summary"
                2 -> "bill-stats-overview"
                3 -> "my-balance-card"
                4 -> "profile-details-card"
                else -> "profile-field-余额"
            }
            val errorTag = if (index < 4) "page-network-error" else "read-notice"
            compose.runOnIdle { page = index; offline = true }
            val message = if (index < 4) "网络连接失败，显示上次数据，请下拉刷新" else "网络连接失败，显示上次数据"
            compose.onNodeWithText(message).assertIsDisplayed()
            compose.onAllNodesWithText(message).assertCountEquals(1)
            compose.onAllNodesWithTag("page-network-error").assertCountEquals(if (index < 4) 1 else 0)
            compose.onAllNodesWithTag("read-notice").assertCountEquals(if (index < 4) 0 else 1)
            compose.onAllNodesWithTag("read-unavailable").assertCountEquals(0)
            compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
            compose.onNodeWithTag(contentTag).assertIsDisplayed()
            if (index == 0) compose.onNodeWithText("86.4").assertIsDisplayed()
            compose.runOnIdle { offline = false; reading = index >= 4 }
            if (index >= 4) {
                compose.onNodeWithText("网络连接失败，显示上次数据").assertIsDisplayed()
                compose.runOnIdle { reading = false }
            }
            compose.onNodeWithTag(errorTag).assertDoesNotExist()
            compose.onNodeWithTag(contentTag).assertIsDisplayed()
            if (index == 0) compose.onNodeWithText("86.4").assertIsDisplayed()
        }
    }

    @Test fun offlineMyAndDetailsWithoutCacheShowFailureInsteadOfNormalValues() {
        val settings = themeSettingsFixture(compose.activity.application)
        var page by mutableIntStateOf(0)
        compose.setContent {
            AppTheme {
                CompositionLocalProvider(LocalInspectionMode provides true, LocalReadOffline provides true) {
                    key(page) {
                        when (page) {
                            0 -> MyPage(MyUiState(), PaymentUiState(), settings, 0.dp, true,
                                {}, {}, {}, {}, {}, false, {}, false, false, {}, {})
                            1 -> PersonalProfilePage(MyUiState(), settings, {}, {})
                            2 -> AccountDetailPage(PaymentUiState(), settings, {}, {}, onRefresh = {})
                        }
                    }
                }
            }
        }
        for (index in 0..2) {
            compose.runOnIdle { page = index }
            compose.onNodeWithText("网络连接失败，请下拉刷新").assertIsDisplayed()
            compose.onAllNodesWithTag("read-unavailable").assertCountEquals(1)
            compose.onAllNodesWithTag("page-network-error").assertCountEquals(0)
            compose.onNodeWithText("网络连接失败，显示上次数据").assertDoesNotExist()
            compose.onNodeWithText("未提供").assertDoesNotExist()
        }
    }

    @Test fun offlineCachedDetailsPreserveAuthenticationAndBusinessFailures() {
        val settings = themeSettingsFixture(compose.activity.application)
        val mine = PreviewData.myState().copy(profileError = "凭证过期", profileErrorKind = ReadFailureKind.Authentication)
        val payment = PreviewData.paymentState(false).copy(cardStatusError = "无法确认卡状态", cardStatusErrorKind = ReadFailureKind.Other)
        var profile by mutableStateOf(true)
        compose.setContent {
            AppTheme {
                CompositionLocalProvider(LocalInspectionMode provides true, LocalReadOffline provides true) {
                    key(profile) {
                        if (profile) PersonalProfilePage(mine, settings, {}, {})
                        else AccountDetailPage(payment, settings, {}, {}, onRefresh = {})
                    }
                }
            }
        }
        compose.onNodeWithText("登录已失效，请重新登录").assertIsDisplayed()
        compose.onAllNodesWithTag("read-notice").assertCountEquals(1)
        compose.runOnIdle { profile = false }
        compose.onNodeWithText("暂时无法更新，显示上次数据").assertIsDisplayed()
        compose.onAllNodesWithTag("read-notice").assertCountEquals(1)
    }

    @Test fun offlineFirstEntryClearsItsDisplayFailureWhenReadingBecomesPossible() {
        var offline by mutableStateOf(true)
        compose.setContent {
            AppTheme {
                CompositionLocalProvider(LocalReadOffline provides offline) {
                    val error = rememberInitialReadError(false, null, true)
                    InitialReadTransition(error) { displayed ->
                        if (displayed != null) ReadUnavailable(displayed)
                        else Text("等待读取")
                    }
                }
            }
        }
        compose.onNodeWithText("网络连接失败，请下拉刷新").assertIsDisplayed()
        compose.runOnIdle { offline = false }
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
        compose.onNodeWithText("等待读取").assertIsDisplayed()
    }

    @Test fun partialOfflineHallPreservesCachedPackageAndShowsUnreadRoomsAsUnavailable() {
        val settings = themeSettingsFixture(compose.activity.application)
        val state = DormUiState(network = FeeQuery(display = listOf("用户套餐" to "缓存套餐")),
            networkLoaded = true, displayRestored = true)
        compose.setContent {
            AppTheme {
                CompositionLocalProvider(LocalInspectionMode provides true, LocalReadOffline provides true) {
                    HallPage(settings, 0.dp, dorm = state, isActive = true)
                }
            }
        }
        compose.onNodeWithText("缓存套餐").assertIsDisplayed()
        compose.onNodeWithText("网络连接失败，显示上次数据，请下拉刷新").assertIsDisplayed()
        compose.onAllNodesWithTag("page-network-error").assertCountEquals(1)
        compose.onNodeWithTag("read-unavailable").assertDoesNotExist()
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在读取房间").assertDoesNotExist()
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
    }

    @Test fun interruptedMeterDiscoveryShowsOfflineFeedbackAndRecoversWithoutLosingReadings() {
        val settings = themeSettingsFixture(compose.activity.application)
        val project = FeeProject.Zhengdong
        val binding = FeeRoomBinding.from(listOf(FeeLevel("room", 1, "房间")),
            mapOf("room" to "fake-room"), mapOf("room" to "示例照明"))!!
        val lighting = DormMeter(DormMeterKind.Lighting, binding,
            FeeQuery(display = listOf("剩余电量(度)" to "86.4")), project = project)
        val pending = DormMeterSlot(DormMeterKind.AirConditioning, project)
        var offline by mutableStateOf(false)
        var room by mutableStateOf(DormRoom(project, binding, listOf(lighting), 1,
            pendingMeters = listOf(pending)))
        compose.setContent {
            AppTheme {
                CompositionLocalProvider(LocalInspectionMode provides true, LocalReadOffline provides offline) {
                    HallPage(settings, 0.dp, dorm = DormUiState(rooms = listOf(room),
                        loaded = true, network = FeeQuery(display = listOf("用户套餐" to "缓存套餐"))), isActive = true)
                }
            }
        }
        compose.onNodeWithText("86.4").assertIsDisplayed()
        compose.onNodeWithText(FeedbackText.Offline).assertDoesNotExist()
        compose.runOnIdle { offline = true }
        compose.onNodeWithText("86.4").assertIsDisplayed()
        compose.onNodeWithText("—").assertIsDisplayed()
        compose.onNodeWithText("网络连接失败，显示上次数据，请下拉刷新").assertIsDisplayed()
        compose.onNodeWithText(FeedbackText.Offline).assertDoesNotExist()
        compose.onAllNodesWithTag("page-network-error").assertCountEquals(1)
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
        compose.runOnIdle {
            offline = false
            room = DormRoom(project, binding, listOf(lighting, DormMeter(DormMeterKind.AirConditioning,
                binding, FeeQuery(display = listOf("剩余电量(度)" to "28.6")), project = project)), 2)
        }
        compose.onNodeWithText(FeedbackText.Offline).assertDoesNotExist()
        compose.onNodeWithText("—").assertDoesNotExist()
        compose.onNodeWithText("86.4").assertIsDisplayed()
        compose.onNodeWithText("28.6").assertIsDisplayed()
    }
}
