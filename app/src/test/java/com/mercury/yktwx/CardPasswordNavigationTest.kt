package com.mercury.yktwx

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.mercury.yktwx.data.*
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardPasswordNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class MemoryVault : Vault {
        var value: Credentials? = Credentials("fake-user", "fake-password")
        var token: Session? = Session("fake-token", Long.MAX_VALUE)
        override fun credentials() = value
        override fun session() = token
        override fun saveCredentials(value: Credentials) { this.value = value }
        override fun saveSession(value: Session) { token = value }
        override fun clearSession() { token = null }
        override fun clear() { value = null; token = null }
    }
    @Test fun myPageSubmissionShowsSuccessToastAndActuallyNavigatesToLoginWithoutRelogin() {
        val app = compose.activity.application
        val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(connectivity).setNetworkCapabilities(connectivity.activeNetwork, capabilities)
        val vault = MemoryVault()
        var writes = 0
        var logins = 0
        val card = CampusCard("fake-card", "校园卡", "202600000001", false, false, 0)
        val service = object : CardService by CardServiceApi({ error("Unexpected real HTTP request") }) {
            override suspend fun passwordConfiguration(token: String) = CardPasswordConfiguration(listOf(card))
            override suspend fun modifyCardPassword(token: String, card: CampusCard, oldPassword: String, newPassword: String, confirmation: String) {
                assertEquals("123456", oldPassword); assertEquals("654321", newPassword); assertEquals(newPassword, confirmation)
                writes++
            }
        }
        val ykt = object : YktService by YktApi({ error("Unexpected real HTTP request") }) {
            override suspend fun login(credentials: Credentials): Session { logins++; error("Must not automatically login") }
        }
        val profiles = object : ProfileService {
            override suspend fun profile(token: String) = PersonalProfile("示例姓名", "fake-user", "示例部门", "", null)
            override suspend fun avatar(path: String): ByteArray? = null
        }
        val repository = AccountRepository(ykt, vault,
            feeService = FeeApi({ error("Unexpected real HTTP request") }),
            cardService = service, profileService = profiles)
        val payment = PaymentViewModel(app, repository)
        val password = CardPasswordViewModel(app, repository)
        val bills = BillViewModel(app, repository)
        val mine = MyViewModel(app, repository)
        val dorm = HallViewModel(app, repository)
        compose.activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" + MyViewModel::class.java.name, mine)
        compose.activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" + HallViewModel::class.java.name, dorm)
        compose.activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:" + CardPasswordViewModel::class.java.name, password)
        assertSame(password, androidx.lifecycle.ViewModelProvider(compose.activity)[CardPasswordViewModel::class.java])
        compose.runOnIdle { payment.initialize(); bills.initialize(true) }
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(ThemeSettings(app), payment, bills) }
        }
        compose.waitUntil(5000) {
            shadowOf(Looper.getMainLooper()).idle()
            !payment.state.value.restoringLogin
        }
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithText("修改查询密码").performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5000) {
            shadowOf(Looper.getMainLooper()).idle()
            password.state.value.visible
        }
        compose.onNode(hasText("原查询密码") and hasSetTextAction()).performTextInput("123456")
        compose.onNode(hasText("新查询密码（6 位数字）") and hasSetTextAction()).performTextInput("654321")
        compose.onNode(hasText("确认新查询密码") and hasSetTextAction()).performTextInput("654321")
        compose.onNodeWithText("确认修改").performScrollTo().performClick()
        compose.onNodeWithText("确认修改").assertDoesNotExist()
        compose.waitUntil(5000) {
            shadowOf(Looper.getMainLooper()).idle()
            payment.state.value.needsLogin && !payment.state.value.busy
        }
        compose.waitForIdle()
        compose.onNodeWithText("登录校园一卡通").assertIsDisplayed()
        compose.onNodeWithText("修改查询密码").assertDoesNotExist()
        assertEquals("查询密码修改成功，请重新登录", ShadowToast.getTextOfLatestToast())
        assertEquals(1, writes); assertEquals(0, logins)
        assertNull(vault.credentials()); assertNull(vault.session())
        assertNull(payment.state.value.code)
    }
}
