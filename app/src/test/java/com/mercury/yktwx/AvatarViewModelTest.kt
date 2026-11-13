package com.mercury.yktwx

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AvatarViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val directory = Files.createTempDirectory("avatar-model-test").toFile()
    private val store = LocalAvatarStore(directory)
    private val viewModels = ViewModelStore()
    private var gate: CountDownLatch? = null
    private var entered = CountDownLatch(1)
    private val uri = Uri.parse("content://fake/photos/avatar")
    private fun image() = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
    private fun model(saved: SavedStateHandle = SavedStateHandle()) = AvatarViewModel(store = store, savedState = saved, decode = {
        entered.countDown(); gate?.await(5, TimeUnit.SECONDS)
        if (it == uri) image() else error("invalid image")
    }).also { viewModels.put("model-${System.nanoTime()}", it) }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Timed out waiting for avatar state")
    }
    @After fun cleanup() { gate?.countDown(); viewModels.clear(); shadowOf(Looper.getMainLooper()).idle(); directory.deleteRecursively() }

    @Test fun savedAvatarSurvivesRestartLogoutAndAccountChangesAndCanBeRestoredOffline() {
        val model = model()
        model.accountChanged("fake-owner"); await { !model.state.value.restoring }
        model.open(); model.imagePicked(uri, model.beginPick()!!)
        await { model.state.value.draft != null }
        assertNull(model.state.value.bytes)
        model.save(); await { !model.state.value.saving }
        val bytes = model.state.value.bytes!!
        assertNotNull(model.state.value.image)
        assertEquals(512, model.state.value.image!!.width)
        assertFalse(model.state.value.visible)
        model.accountChanged(""); assertNull(model.state.value.bytes)
        model.accountChanged("another-owner"); await { !model.state.value.restoring }
        assertNull(model.state.value.bytes)
        assertNull(model.state.value.image)
        val restarted = model()
        restarted.accountChanged("fake-owner"); await { !restarted.state.value.restoring }
        assertArrayEquals(bytes, restarted.state.value.bytes)
        assertNotNull(restarted.state.value.image)
        restarted.open(); restarted.restoreOfficial(); await { !restarted.state.value.saving }
        assertNull(restarted.state.value.bytes); assertNull(store.read("fake-owner"))
        assertNull(restarted.state.value.image)
    }

    @Test fun cropRestoresFromItsUriInANewModelWithoutWritingAnAvatar() {
        val handle = SavedStateHandle(); val before = model(handle)
        before.accountChanged("owner", accountGeneration = 20); await { !before.state.value.restoring }
        before.open(); before.imagePicked(uri, before.beginPick()!!)
        await { before.state.value.draft != null }
        before.cropChanged(AvatarCrop(2f, .45f, .6f))
        val crop = before.state.value.crop; val oldBitmap = before.state.value.draft
        val restored = handle.recreated(); viewModels.clear()
        val after = model(restored); after.accountChanged("owner", accountGeneration = 1)
        await { after.state.value.draft != null && !after.state.value.restoring }
        assertTrue(after.state.value.visible); assertEquals(crop, after.state.value.crop)
        assertNotSame(oldBitmap, after.state.value.draft); assertNull(store.read("owner"))
        assertFalse(after.state.value.saving)
        after.accountChanged("owner", accountGeneration = 2)
        assertFalse(after.state.value.visible); assertNull(after.state.value.draft)
    }

    @Test fun pendingPickerTokenSurvivesProcessLossAndAnotherOwnerCannotUseIt() {
        val handle = SavedStateHandle(); val before = model(handle)
        before.accountChanged("owner"); await { !before.state.value.restoring }
        before.open(); val token = before.beginPick()!!
        val restored = handle.recreated(); val otherState = handle.recreated(); viewModels.clear()
        val after = model(restored); after.accountChanged("owner")
        await { !after.state.value.restoring }
        assertTrue(after.state.value.visible); assertFalse(after.state.value.loading)
        after.imagePicked(uri, token); await { after.state.value.draft != null }
        val other = model(otherState); other.accountChanged("other")
        await { !other.state.value.restoring }; other.open(); other.imagePicked(uri, token)
        assertTrue(other.state.value.visible); assertNull(other.state.value.draft)
    }

    @Test fun anUnavailableRestoredUriKeepsTheEditorOpenForAReplacement() {
        val handle = SavedStateHandle(); val before = model(handle)
        before.accountChanged("owner"); await { !before.state.value.restoring }
        before.open(); before.imagePicked(uri, before.beginPick()!!); await { before.state.value.draft != null }
        val restored = handle.recreated(); viewModels.clear()
        val after = AvatarViewModel(store = store, savedState = restored, decode = { throw SecurityException("grant expired") })
            .also { viewModels.put("expired-uri", it) }
        after.accountChanged("owner"); await { after.state.value.error != null && !after.state.value.restoring }
        assertTrue(after.state.value.visible); assertNull(after.state.value.draft)
        assertFalse(after.state.value.loading); assertNull(store.read("owner"))
    }

    @Test fun cancelAndInvalidImportLeaveThePreviousAvatarUntouched() {
        val bytes = AvatarImage.encode(image(), AvatarCrop())
        store.write("fake-owner", bytes)
        val model = model()
        model.accountChanged("fake-owner"); await { !model.state.value.restoring }
        model.open(); model.imagePicked(null, model.beginPick()!!)
        assertFalse(model.state.value.loading)
        model.imagePicked(Uri.parse("content://fake/invalid"), model.beginPick()!!)
        await { !model.state.value.loading }
        assertNotNull(model.state.value.error); assertArrayEquals(bytes, model.state.value.bytes)
        model.imagePicked(uri, model.beginPick()!!); await { model.state.value.draft != null }
        model.dismiss()
        assertNull(model.state.value.draft); assertArrayEquals(bytes, store.read("fake-owner"))
    }

    @Test fun slowPickerResultCannotReopenAnEditorAfterAccountChanges() {
        val model = model()
        model.accountChanged("fake-owner"); await { !model.state.value.restoring }
        model.open()
        val token = model.beginPick()!!
        gate = CountDownLatch(1)
        model.imagePicked(uri, token)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        model.accountChanged("another-owner")
        gate!!.countDown(); await { !model.state.value.restoring }
        model.imagePicked(uri, token)
        assertFalse(model.state.value.visible); assertNull(model.state.value.draft)
        assertNull(model.state.value.bytes); assertNull(store.read("fake-owner"))
    }

    @Test fun oldPickerTokenCannotAffectAReopenedSession() {
        val model = model()
        model.accountChanged("fake-owner"); await { !model.state.value.restoring }
        model.open(); val old = model.beginPick()!!
        model.dismiss(); model.open(); val fresh = model.beginPick()!!
        model.imagePicked(uri, old)
        assertTrue(model.state.value.loading); assertNull(model.state.value.draft)
        model.imagePicked(null, fresh)
        assertFalse(model.state.value.loading)
    }

    @Test fun storageFailureKeepsTheDraftForRetryAndDoesNotReplaceTheAvatar() {
        val blocked = java.io.File(directory, "not-a-directory").apply { writeText("test") }
        val model = avatarViewModelFixture(app, LocalAvatarStore(blocked)) { image() }
        viewModels.put("blocked", model)
        model.accountChanged("fake-owner"); await { !model.state.value.restoring }
        model.open(); model.imagePicked(uri, model.beginPick()!!)
        await { model.state.value.draft != null }
        model.save(); await { !model.state.value.saving }
        assertTrue(model.state.value.visible)
        assertNotNull(model.state.value.draft)
        assertNull(model.state.value.bytes)
        assertEquals("头像保存失败，请重试", model.state.value.error)
    }

    @Test fun openingBeforeRestoreCannotReplaceAnUnreadAvatarAndPreviewRemainsIsolated() {
        val bytes = AvatarImage.encode(image(), AvatarCrop())
        store.write("fake-owner", bytes)
        val model = model()
        synchronized(store) {
            model.accountChanged("fake-owner")
            model.open()
            assertTrue(model.state.value.restoring)
            assertNull(model.beginPick())
        }
        await { !model.state.value.restoring }
        assertArrayEquals(bytes, model.state.value.bytes)
        assertTrue(model.state.value.visible)
        model.accountChanged("fake-owner", preview = true)
        await { !model.state.value.restoring }
        assertNull(model.state.value.bytes)
        assertFalse(model.state.value.visible)
        model.accountChanged("fake-owner")
        await { !model.state.value.restoring }
        assertArrayEquals(bytes, model.state.value.bytes)
    }
}
