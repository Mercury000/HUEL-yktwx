package com.mercury.yktwx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.AnimationUtils
import android.view.animation.TranslateAnimation
import androidx.test.core.app.ApplicationProvider
import com.mercury.yktwx.data.life.LifeProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.nav.runtime.NavProgrammaticEasing

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ScannerWindowMotionTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun bothStylesLoadThroughTheFrameworkAndKeepTheirOwnDurations() {
        for (style in 0..1) {
            val motion = ScannerWindowMotion.forStyle(style)
            val duration = if (style == 0) 500L else 450L
            for (resource in listOf(motion.openEnter, motion.openExit, motion.closeEnter, motion.closeExit)) {
                val animation = AnimationUtils.loadAnimation(context, resource) as AnimationSet
                animation.initialize(1080, 2400, 1080, 2400)
                assertEquals(duration, animation.animations.filterIsInstance<TranslateAnimation>().single().duration)
            }
            if (style == 1) {
                for ((resource, offset) in listOf(motion.openEnter to 50L, motion.closeExit to 35L)) {
                    val animation = AnimationUtils.loadAnimation(context, resource) as AnimationSet
                    animation.initialize(1080, 2400, 1080, 2400)
                    val fade = animation.animations.filterIsInstance<AlphaAnimation>().single()
                    // A duration on the parent set would overwrite this independent 83ms track.
                    assertEquals(83L, fade.duration)
                    assertEquals(offset, fade.startOffset)
                    assertEquals(0.5f, fade.interpolator.getInterpolation(0.5f), 0.00001f)
                }
            }
        }
    }

    @Test fun frameworkMiuixPathFollowsTheLibraryEasing() {
        val curve = AnimationUtils.loadInterpolator(context, R.anim.miuix_window_interpolator)
        for (sample in 0 until 1000) {
            val fraction = sample / 1000f
            assertEquals(NavProgrammaticEasing.transform(fraction), curve.getInterpolation(fraction), 0.0003f)
        }
        assertEquals(1f, curve.getInterpolation(1f), 0f)
    }

    @Test fun resultsRetainManualPlatformAndRejectCancellationOrUnknownQr() {
        val raw = "https://i.hnkzy.com/q/1/00001234"
        val qr = LifeScannerResult.read(Activity.RESULT_OK, LifeScannerResult(raw).toIntent())!!
        assertEquals(LifeProvider.Hui798, qr.route()!!.provider)
        assertEquals("00001234", qr.route()!!.code)
        val number = "123456789012345"
        for (provider in listOf(LifeProvider.Panggui, LifeProvider.Hui798)) {
            val result = LifeScannerResult.read(Activity.RESULT_OK, LifeScannerResult(number, provider).toIntent())!!
            assertEquals(provider, result.route()!!.provider)
        }
        assertNull(LifeScannerResult.read(Activity.RESULT_CANCELED, qr.toIntent()))
        assertNull(LifeScannerResult.read(Activity.RESULT_OK, LifeScannerResult("unknown").toIntent()))
        assertNull(LifeScannerResult.read(Activity.RESULT_OK, LifeScannerResult(raw, LifeProvider.Panggui).toIntent()))
    }

    @Test fun appearanceSnapshotCarriesTheAppSettingsAndUsesLegacyDefaults() {
        val configured = ScannerAppearance(themeMode = 2, accentMode = 1, glassStyle = true)
        assertEquals(configured, ScannerAppearance.read(ScannerActivity.intent(context, transitionStyle = 1, configured)))
        assertEquals(ScannerAppearance(themeMode = 0, accentMode = 0, glassStyle = false),
            ScannerAppearance.read(Intent()))
    }
}
