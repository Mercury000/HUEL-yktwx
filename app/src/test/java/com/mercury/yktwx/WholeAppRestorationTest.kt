package com.mercury.yktwx

import android.content.Intent
import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.ui.nav.AppRoute
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h480dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WholeAppRestorationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun newActivityRestoresScannerManualDraftWithoutIdentifyingDevice() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).putExtra("ui_preview", true)
        var controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible()
        try {
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("payment-scan-button").assertIsEnabled() }.isSuccess }
            compose.onNodeWithTag("payment-scan-button").performClick()
            compose.onNodeWithText("输入设备编号").performClick()
            compose.onNodeWithTag("life-device-serial").performTextReplacement("12345")
            compose.onNodeWithText("识别设备").assertIsNotEnabled()
            val oldModel = ViewModelProvider(controller.get())[LifeDevicesViewModel::class.java]
            val saved = Bundle()
            compose.runOnIdle { controller.pause().saveInstanceState(saved).stop().destroy() }
            val restored = Parcel.obtain().let { parcel ->
                try {
                    parcel.writeBundle(saved)
                    val bytes = parcel.marshall()
                    parcel.unmarshall(bytes, 0, bytes.size); parcel.setDataPosition(0)
                    requireNotNull(parcel.readBundle(MainActivity::class.java.classLoader))
                } finally { parcel.recycle() }
            }
            controller = Robolectric.buildActivity(MainActivity::class.java, intent).create(restored).start().resume().visible()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("life-device-serial").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("life-device-serial").assertTextContains("12345")
            compose.onNodeWithText("识别设备").assertIsNotEnabled()
            val model = ViewModelProvider(controller.get())[LifeDevicesViewModel::class.java]
            assertNotSame(oldModel, model)
            assertTrue(model.state.value.scanning)
            assertNull(model.state.value.device)
            assertNull(model.state.value.paymentLaunch)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun newActivityAndViewModelsRestoreTypedRoutesSelectedTabAndThemeScroll() {
        val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).putExtra("ui_preview", true)
        var controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible()
        try {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("我的") and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasText("我的") and isSelectable()).performClick()
            compose.onNodeWithTag("my-page-list").performScrollToNode(hasText("应用设置"))
            compose.onNodeWithText("应用设置").performScrollTo().performClick()
            compose.onNodeWithText("主题").performClick()
            val title = controller.get().getString(R.string.swipe_back)
            compose.onNodeWithText(title).performScrollTo()
            fun list() = compose.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText(title)))
            list().performTouchInput { swipeUp() }
            val scrollBefore = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue(scrollBefore > 0f)
            val oldActivity = controller.get()
            val oldNavigation = ViewModelProvider(oldActivity)[AppNavigationViewModel::class.java]
            val oldScope = oldNavigation.scope
            assertEquals(listOf(AppRoute.Main, AppRoute.Settings, AppRoute.Theme), oldNavigation.backStack.toList())
            val saved = Bundle()
            compose.runOnIdle { controller.pause().saveInstanceState(saved).stop().destroy() }
            val restored = Parcel.obtain().let { parcel ->
                try {
                    parcel.writeBundle(saved)
                    val bytes = parcel.marshall()
                    parcel.unmarshall(bytes, 0, bytes.size); parcel.setDataPosition(0)
                    requireNotNull(parcel.readBundle(MainActivity::class.java.classLoader))
                } finally { parcel.recycle() }
            }
            controller = Robolectric.buildActivity(MainActivity::class.java, intent).create(restored).start().resume().visible()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            val navigation = ViewModelProvider(controller.get())[AppNavigationViewModel::class.java]
            assertNotSame(oldActivity, controller.get())
            assertNotSame(oldNavigation, navigation)
            assertEquals(oldScope, navigation.scope)
            assertEquals(3, navigation.tab)
            assertEquals(listOf(AppRoute.Main, AppRoute.Settings, AppRoute.Theme), navigation.backStack.toList())
            assertEquals(scrollBefore, list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), .001f)
            compose.runOnIdle { controller.get().onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("settings-page-list").assertIsDisplayed()
        } finally { controller.pause().stop().destroy() }
    }
}
