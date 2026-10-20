package com.mercury.yktwx

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var settings: ThemeSettings
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = ThemeSettings(context)
    }
    private fun content(preview: Boolean = true) {
        val model = PaymentViewModel(compose.activity.application)
        model.initialize(preview)
        compose.setContent { YktApp(settings, model) }
        compose.waitForIdle()
    }
    @Test fun firstLaunchShowsLoginAndSystemPasswordField() {
        content(false)
        compose.onNodeWithText("登录校园一卡通").assertIsDisplayed()
        compose.onNodeWithText("学号 / 工号").assertExists()
        compose.onNodeWithText("密码").assertExists()
        compose.onNodeWithContentDescription("显示密码").assertExists().performClick()
        compose.onNodeWithContentDescription("隐藏密码").assertExists()
        compose.onNodeWithText("打开官网处理验证码 / 账户问题").assertDoesNotExist()
        compose.onNodeWithText("登录并获取付款码").assertIsNotEnabled()
    }
    @Test fun paymentPreviewAndRefreshButtonExist() {
        content()
        compose.onNodeWithContentDescription("刷新付款码").assertExists()
        compose.onNodeWithContentDescription("付款条形码").assertExists()
        compose.onNodeWithContentDescription("付款二维码").assertExists()
    }
    @Test fun aboutSettingsThemeNavigateAndPersist() {
        content()
        compose.onNodeWithText("关于").performClick()
        compose.onNodeWithText("设置").performScrollTo().performClick()
        compose.onNodeWithText("主题").performClick()
        compose.onNodeWithText("预测性返回动画").assertExists()
        compose.onNodeWithText("顶栏模糊").assertExists()
        compose.runOnIdle { settings.setTheme(2); settings.setAccent(1); settings.setFloating(true); settings.setFloatingStyle(1) }
        compose.waitForIdle()
        compose.onNodeWithText("主题颜色").assertExists()
        val restored = ThemeSettings(compose.activity)
        assertEquals(2, restored.themeMode.value)
        assertEquals(1, restored.accentMode.value)
        assertTrue(restored.floatingBottomBar.value)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("主题").assertExists()
    }
}
