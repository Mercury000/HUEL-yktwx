import java.util.Properties

plugins {
    id("com.android.application")
    id("com.mikepenz.aboutlibraries.plugin.android") version "15.3.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21"
}
val machineProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val pangguiSecret = providers.environmentVariable("PANGGUI_API_SECRET")
    .orElse(machineProperties.getProperty("panggui.apiSecret", "")).get()
val hui798Gateway = providers.environmentVariable("HUI798_API_GATEWAY")
    .orElse(machineProperties.getProperty("hui798.apiGateway", "https://i.ilife798.com/api/v1")).get()
val hui798Salt = providers.environmentVariable("HUI798_SIGN_SALT")
    .orElse(machineProperties.getProperty("hui798.signSalt", "")).get()
fun buildString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
android {
    namespace = "com.mercury.yktwx"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.mercury.yktwx"
        minSdk = 34
        targetSdk = 37
        versionCode = 9
        versionName = "1.4.1"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PANGGUI_API_SECRET", "\"" + pangguiSecret.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        buildConfigField("String", "HUI798_API_GATEWAY", buildString(hui798Gateway))
        buildConfigField("String", "HUI798_SIGN_SALT", buildString(hui798Salt))
    }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = true
    }
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
    lint {
        // Match the compilation JDK even when Gradle runs on an older JVM.
        toolchain.languageVersion.set(JavaLanguageVersion.of(22))
    }
    buildTypes {
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            resValue("string", "app_name", "财小通 Preview")
            matchingFallbacks += "debug"
        }
        create("personal") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    sourceSets {
        // Only these builds compile UI fixtures, preview notes and intent extras.
        listOf("debug", "preview").forEach { getByName(it).kotlin.directories.add("src/uiPreview/kotlin") }
        listOf("release", "personal").forEach { getByName(it).kotlin.directories.add("src/production/kotlin") }
    }
}
androidComponents {
    beforeVariants(selector().all()) { variant ->
        // Exercise both source-set implementations; AGP only enables debug tests by default.
        variant.hostTests.values.filter { it.type == com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE }
            .forEach { it.enable = true }
    }
    onVariants(selector().all()) { variant ->
        // Compress distributable APKs; keep local debug/preview packaging fast.
        variant.packaging.dex.useLegacyPackaging.set(!variant.debuggable)
    }
}
tasks.withType<Test>().configureEach {
    // Existing UI tests require debug fixtures and its test activity manifest.
    if (name in setOf("testPreviewUnitTest", "testReleaseUnitTest", "testPersonalUnitTest")) {
        filter { includeTestsMatching("com.mercury.yktwx.UiPreviewIsolationTest") }
    }
}
java { toolchain { languageVersion = JavaLanguageVersion.of(22) } }
kotlin { jvmToolchain(22) }
dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Payment QR and barcode generation; camera decoding uses wechat-qrcode-lite.
    implementation("com.google.zxing:core:3.5.4")
    implementation(files("libs/wechat-qrcode-lite-0.1.0-arm64-v8a.aar"))
    implementation("com.alipay.sdk:alipaysdk-android:15.8.42")
    implementation(files("libs/unionpay-sdk-mini-3.5.16.jar"))
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")
    implementation("com.mikepenz:aboutlibraries-core:15.3.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.json:json:20260814")
}

aboutLibraries {
    offlineMode = true
    collect {
        includePlatform = false
        // Bundle license texts without fetching them during the build.
        configPath.set(layout.projectDirectory.dir("config/aboutlibraries"))
    }
    export { prettyPrint = true }
}
