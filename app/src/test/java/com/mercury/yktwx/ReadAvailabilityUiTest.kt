package com.mercury.yktwx

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
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import com.mercury.yktwx.ui.pages.HallPage
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
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
        val settings = ThemeSettings(compose.activity.application)
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
        compose.onNodeWithTag("read-unavailable").assertIsDisplayed()
        compose.onNodeWithContentDescription("正在读取房间").assertDoesNotExist()
        compose.onNodeWithTag("page-read-progress").assertDoesNotExist()
    }

    @Test fun interruptedMeterDiscoveryShowsOfflineFeedbackAndRecoversWithoutLosingReadings() {
        val settings = ThemeSettings(compose.activity.application)
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
        compose.onNodeWithText(FeedbackText.Offline).assertIsDisplayed()
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
