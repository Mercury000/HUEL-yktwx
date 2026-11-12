package com.mercury.yktwx

import com.mercury.yktwx.ui.themeSettingsFixture
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.YktApp
import com.mercury.yktwx.data.AvatarCrop
import com.mercury.yktwx.data.AvatarImage
import com.mercury.yktwx.data.LocalAvatarStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<HiltUiTestActivity>()
    private lateinit var settings: ThemeSettings
    private val model get() = ViewModelProvider(compose.activity)[AvatarViewModel::class.java]

    private fun setup() {
        val app = compose.activity.application
        app.getSharedPreferences("ui_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = themeSettingsFixture(app).apply { setTheme(1) }
        val payments = paymentViewModelFixture(app).apply { initialize(true) }
        val bills = billViewModelFixture(app).apply { initialize(true) }
        compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) } }
        compose.onNode(hasText("我的") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("个人信息").performClick()
        compose.onNodeWithContentDescription("更换头像").performClick()
        compose.onNodeWithText("从相册选择").assertIsDisplayed()
        compose.onNodeWithText("恢复校园头像").assertDoesNotExist()
    }

    private fun image(): Uri {
        val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(209, 225, 222))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(70, 115, 124)
        canvas.drawCircle(450f, 440f, 200f, paint)
        paint.color = Color.rgb(39, 78, 87)
        canvas.drawRoundRect(180f, 700f, 720f, 1300f, 220f, 220f, paint)
        val file = File(compose.activity.cacheDir, "fictional-avatar.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    private fun pick(uri: Uri?) {
        compose.onNodeWithText("从相册选择").performClick()
        compose.runOnIdle {
            val request = shadowOf(compose.activity).nextStartedActivityForResult
            assertNotNull("A real system photo picker must launch", request)
            assertEquals("image/*", request.intent.type)
            compose.activity.activityResultRegistry.dispatchResult(request.requestCode,
                if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                uri?.let { Intent().setData(it) })
        }
        compose.waitUntil(5_000) { !model.state.value.loading }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        // Android measures the new page after the first animation pass; settle its size transition too.
        repeat(2) { compose.mainClock.advanceTimeBy(600); compose.waitForIdle() }
        // Robolectric's native renderer can return the previous draw on the first root capture.
        compose.onRoot().captureToImage()
        compose.waitForIdle()
        val file = File("../tools/avatar-review/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun systemPickerCropSaveAndRestoreUpdateBothAvatarLocations() {
        setup(); capture("choose-light")
        pick(image())
        compose.onNodeWithTag("avatar-crop").assertIsDisplayed()
        compose.onNodeWithText("使用").assertIsDisplayed()
        capture("crop-centered-light")
        val button = compose.onNodeWithText("使用").fetchSemanticsNode().boundsInRoot
        val screen = compose.onRoot().captureToImage().asAndroidBitmap()
        val pixel = screen.getPixel((button.left + button.width * .2f).toInt(), button.center.y.toInt())
        assertTrue("The confirm button must actually be drawn after the page transition", Color.blue(pixel) > Color.red(pixel) + 30)
        val before = model.state.value.crop
        compose.onNodeWithTag("avatar-crop").performTouchInput { swipeUp() }
        assertTrue("Dragging must adjust the crop without closing the sheet", before != model.state.value.crop)
        compose.onNodeWithTag("avatar-crop").performTouchInput {
            down(0, Offset(width * .35f, height * .5f))
            down(1, Offset(width * .65f, height * .5f))
            repeat(8) { step ->
                moveTo(0, Offset(width * (.35f - step * .02f), height * .5f), delayMillis = 16)
                moveTo(1, Offset(width * (.65f + step * .02f), height * .5f), delayMillis = 16)
            }
            up(0); up(1)
        }
        assertTrue(model.state.value.crop.zoom > 1f)
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("使用").assertIsDisplayed()
        capture("crop-light")
        compose.runOnIdle { settings.setTheme(2) }
        capture("crop-dark")
        compose.onNodeWithText("使用").performTouchInput { click() }
        compose.waitUntil(5_000) { model.state.value.bytes != null && !model.state.value.saving }
        compose.onNodeWithContentDescription("更换头像").assertExists()
        compose.onNode(hasContentDescription("更换头像") and hasContentDescription("自定义头像")).assertExists()
        capture("profile-custom-dark")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNode(hasContentDescription("个人信息") and hasContentDescription("自定义头像")).assertExists()
        capture("mine-custom-dark")
        compose.onNodeWithContentDescription("个人信息").performClick()
        compose.onNodeWithContentDescription("更换头像").performClick()
        compose.onNodeWithText("恢复校园头像").assertIsDisplayed()
        capture("choose-custom-dark")
        compose.onNodeWithText("恢复校园头像").performClick()
        compose.waitUntil(5_000) { model.state.value.bytes == null && !model.state.value.visible }
        compose.onAllNodesWithContentDescription("自定义头像").assertCountEquals(0)
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h740dp-xxhdpi", fontScale = 1.5f)
    fun cancelledPickerAndCropLeaveTheExistingAvatarAndBackOnlyClosesTheEditor() {
        setup()
        pick(null)
        compose.onNodeWithText("从相册选择").assertIsDisplayed()
        assertNull(model.state.value.bytes)
        pick(image())
        capture("crop-narrow")
        compose.onNodeWithText("使用").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.waitForIdle()
        assertNull(model.state.value.bytes)
        compose.onNodeWithContentDescription("更换头像").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("从相册选择").assertDoesNotExist()
        compose.onNodeWithText("学工号").assertIsDisplayed()
        assertNull(model.state.value.draft)
    }

    @Test fun coldLocalRestoreNeverShowsDefaultOrOfficialAvatarAndReadyFramesDrawTheStoredImage() {
        val app = compose.activity.application
        val payments = paymentViewModelFixture(app).apply { initialize(true) }
        val bills = billViewModelFixture(app).apply { initialize(true) }
        val store = LocalAvatarStore(app)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val bytes = AvatarImage.encode(bitmap, AvatarCrop())
        bitmap.recycle()
        val owner = "preview:${payments.state.value.username}"
        store.write(owner, bytes)
        val avatars = avatarViewModelFixture(app, store)
        compose.activity.viewModelStore.put("androidx.lifecycle.ViewModelProvider.DefaultKey:com.mercury.yktwx.AvatarViewModel", avatars)
        settings = themeSettingsFixture(app).apply { setTheme(1) }
        synchronized(store) {
            compose.setContent { CompositionLocalProvider(LocalInspectionMode provides true) { YktApp(settings, payments, bills) } }
            compose.runOnIdle { assertSame(avatars, model) }
            compose.onNode(hasText("我的") and hasClickAction()).performClick()
            assertTrue(avatars.state.value.restoring)
            compose.onNodeWithContentDescription("个人信息").assertIsDisplayed()
            compose.onAllNodesWithContentDescription("头像").assertCountEquals(0)
            compose.onAllNodesWithContentDescription("校园系统头像").assertCountEquals(0)
        }
        compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); !avatars.state.value.restoring }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        repeat(4) {
            compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
            compose.onNodeWithContentDescription("头像").assertDoesNotExist()
            val image = compose.onNodeWithContentDescription("自定义头像", useUnmergedTree = true)
                .captureToImage().asAndroidBitmap()
            val pixel = image.getPixel(image.width / 2, image.height / 2)
            assertTrue("Local restores must draw fully without placeholder crossfade", Color.red(pixel) > 240 && Color.blue(pixel) < 20)
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("个人信息").performClick()
        compose.onNode(hasContentDescription("更换头像") and hasContentDescription("自定义头像")).assertIsDisplayed()
        compose.runOnIdle { ViewModelProvider(compose.activity)[MyViewModel::class.java].refresh() }
        compose.onNode(hasContentDescription("更换头像") and hasContentDescription("自定义头像")).assertIsDisplayed()
        assertArrayEquals(bytes, avatars.state.value.bytes)
        store.remove(owner)
    }
}
