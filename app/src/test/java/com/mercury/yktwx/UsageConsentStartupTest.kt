package com.mercury.yktwx

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.mercury.yktwx.data.UsageNotice
import com.mercury.yktwx.ui.UsageConsent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h740dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UsageConsentStartupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualStartupDefersAccountInitializationUntilTheNoticeIsConfirmed() {
        val payments = ViewModelProvider(compose.activity)[PaymentViewModel::class.java]
        val bills = ViewModelProvider(compose.activity)[BillViewModel::class.java]
        compose.onNodeWithText("同意并继续").assertDoesNotExist()
        assertTrue(payments.state.value.restoringLogin)
        assertFalse(bills.state.value.loaded)
        assertTrue(payments.state.value.restoringLogin)
        compose.onNodeWithText("学号 / 工号").performTextInput("202600000001")
        compose.onNodeWithText("密码").performTextInput("fictional-password")
        compose.onNodeWithContentDescription("显示密码").performClick()
        compose.onNodeWithTag("usage-consent-checkbox").performClick()
        compose.onNodeWithTag("usage-notice-body").performScrollToIndex(UsageNotice.paragraphs.lastIndex)
        compose.onNodeWithText("同意并继续").performClick()
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); !payments.state.value.restoringLogin }
        assertTrue(UsageConsent(compose.activity).accepted)
        compose.onNodeWithText("同意并继续").assertDoesNotExist()
        compose.onNodeWithText("登录校园一卡通").assertIsDisplayed()
        compose.onNodeWithText("学号 / 工号").assertTextContains("202600000001")
        compose.onNodeWithText("密码").assertTextContains("fictional-password")
        compose.onNodeWithText("登录").assertIsEnabled()
        assertTrue(payments.state.value.needsLogin)
    }

    @Test fun differentStoredHashRequiresReadingAgainAcrossActivityRecreation() {
        compose.onNodeWithTag("usage-consent-checkbox").performClick()
        compose.onNodeWithTag("usage-notice-body").performScrollToIndex(UsageNotice.paragraphs.lastIndex)
        compose.onNodeWithText("同意并继续").performClick()
        compose.waitUntil(5_000) { UsageConsent(compose.activity).accepted }
        compose.runOnIdle { compose.activity.getSharedPreferences("usage_consent", Context.MODE_PRIVATE)
            .edit().putString("accepted_hash", "previous-body-hash").commit() }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("同意并继续").assertDoesNotExist()
        assertEquals("使用须知已更新，请重新阅读并同意后登录。", ShadowToast.getTextOfLatestToast())
        compose.onNodeWithText("使用须知已更新，请重新阅读并同意后登录。").assertDoesNotExist()
        compose.onNodeWithTag("usage-consent-checkbox").performClick()
        compose.onNodeWithText("同意并继续").assertIsNotEnabled()
        assertFalse(UsageConsent(compose.activity).accepted)
        compose.onNodeWithTag("usage-notice-paragraph-0").assertIsDisplayed()
        compose.onNodeWithText("付款").assertDoesNotExist()
    }
}
