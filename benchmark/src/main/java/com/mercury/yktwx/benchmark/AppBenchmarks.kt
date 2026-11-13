package com.mercury.yktwx.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class AppBenchmarks(private val compilation: CompilationMode) {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldStartup() = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilation,
        startupMode = StartupMode.COLD,
        iterations = 10,
        setupBlock = { pressHome() },
    ) { startFixture() }

    @Test fun navigationAndBills() = benchmark.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = compilation,
        iterations = 5,
        setupBlock = {
            pressHome()
            startFixture()
        },
    ) {
        device.visitMainPages()
        device.visitBillDetail()
        device.scrollBills()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun compilationModes(): List<Array<CompilationMode>> = listOf(
            arrayOf(CompilationMode.None()),
            arrayOf(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)),
        )
    }
}
