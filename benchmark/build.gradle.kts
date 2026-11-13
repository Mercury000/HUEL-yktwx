plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.mercury.yktwx.benchmark"
    compileSdk = 37
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 34
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("performance") { isDebuggable = true }
    }
}

androidComponents {
    beforeVariants(selector().all()) {
        it.enable = it.buildType in setOf("nonMinifiedPerformance", "benchmarkPerformance")
    }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(22) } }
kotlin { jvmToolchain(22) }

baselineProfile {
    useConnectedDevices = true
    skipBenchmarksOnEmulator = true
}

dependencies {
    implementation(libs.benchmark.macro.junit4)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.junit)
    implementation(libs.uiautomator)
    implementation(libs.junit)
}
