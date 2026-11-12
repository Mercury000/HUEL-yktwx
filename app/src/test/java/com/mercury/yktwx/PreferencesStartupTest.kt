package com.mercury.yktwx

import android.app.Application
import android.os.Looper
import android.view.WindowManager
import androidx.datastore.core.DataStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h740dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreferencesStartupTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loaded = CompletableDeferred<AppPreferences>()
    private var fail = false
    private val store = object : DataStore<AppPreferences> {
        override val data = flow { if (fail) throw java.io.IOException("temporary read failure"); emit(loaded.await()); awaitCancellation() }
        override suspend fun updateData(transform: suspend (AppPreferences) -> AppPreferences) = transform(loaded.await())
    }
    private val contextField = AppPreferencesRepository::class.java.getDeclaredField("sharedContext").apply { isAccessible = true }
    private val repositoryField = AppPreferencesRepository::class.java.getDeclaredField("sharedRepository").apply { isAccessible = true }
    private var oldContext: Any? = null
    private var oldRepository: Any? = null
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var activity: MainActivity

    private fun launch() {
        oldContext = contextField.get(null); oldRepository = repositoryField.get(null)
        contextField.set(null, app); repositoryField.set(null, AppPreferencesRepository(store, scope))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity = it }
        compose.waitForIdle()
    }
    @After fun cleanup() {
        scenario?.close(); scope.cancel()
        contextField.set(null, oldContext); repositoryField.set(null, oldRepository)
    }
    private fun protected() = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    private fun hasBusinessModel() = activity.viewModelStore.keys().any {
        val model = activity.viewModelStore[it]
        model is PaymentViewModel || model is BillViewModel || model is RechargeViewModel || model is FeeViewModel
    }
    @Test fun loadingProtectsTheWindowAndLifecycleCallbacksDoNotCreateBusinessModels() {
        launch()
        compose.onNodeWithText("正在准备应用").assertIsDisplayed()
        assertTrue(protected()); assertFalse(hasBusinessModel())
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        assertTrue(protected()); assertFalse(hasBusinessModel())
        loaded.complete(AppPreferences(legacyMigrated = true, ui = UiPreferences(preventScreenshots = false)))
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag("preferences-entry").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        assertFalse(protected()); assertTrue(hasBusinessModel())
        compose.onNodeWithTag("usage-notice-body").assertExists()
    }
    @Test fun failedReadShowsRetryAndPreservesProtectionUntilReadiness() {
        fail = true; launch()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("重试").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(protected()); assertFalse(hasBusinessModel())
        fail = false
        loaded.complete(AppPreferences(legacyMigrated = true))
        compose.onNodeWithText("重试").performClick()
        compose.waitUntil(5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag("preferences-entry").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        assertTrue(protected()); assertTrue(hasBusinessModel())
    }
}
