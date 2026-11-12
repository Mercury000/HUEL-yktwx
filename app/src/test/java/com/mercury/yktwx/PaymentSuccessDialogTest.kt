package com.mercury.yktwx

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.mercury.yktwx.ui.AppTheme
import com.mercury.yktwx.ui.pages.PaymentSuccessDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Scaffold
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaymentSuccessDialogTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()

    @Test fun sheetShowsDifferenceClosesAndUpdatesForNextPayment() {
        val amount = mutableStateOf<Long?>(null)
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppTheme { Scaffold { PaymentSuccessDialog(amount.value) { amount.value = null } } }
            }
        }
        compose.onNodeWithText("支付成功").assertDoesNotExist()
        compose.runOnIdle { amount.value = 500L }
        compose.onNodeWithText("支付成功").assertIsDisplayed()
        compose.onNodeWithText("¥ 5.00").assertIsDisplayed()
        compose.onNodeWithText("正在确认结果").assertDoesNotExist()
        val output = File("../build/payment-success-sheet.png").absoluteFile
        output.parentFile?.mkdirs()
        output.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("完成").performClick()
        compose.runOnIdle { assertNull(amount.value) }
        compose.onNodeWithText("支付成功").assertDoesNotExist()
        compose.runOnIdle { amount.value = 1250L }
        compose.onNodeWithText("¥ 12.50").assertIsDisplayed()
        compose.onNodeWithText("¥ 5.00").assertDoesNotExist()
    }
}
