import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
// Only Actions can enable analytics; local release builds stay disabled even with credentials.
val umengEnabled = providers.environmentVariable("GITHUB_ACTIONS").orNull == "true" &&
    providers.environmentVariable("UMENG_ENABLED").orNull == "true"
val umengAppKey = providers.environmentVariable("UMENG_APP_KEY")
    .orElse(machineProperties.getProperty("umeng.appKey", "")).get().trim()
val umengChannel = providers.environmentVariable("UMENG_CHANNEL")
    .orElse(machineProperties.getProperty("umeng.channel", "")).get().trim()
require(umengAppKey.isEmpty() || umengAppKey.matches(Regex("[a-fA-F0-9]{24}")))
require(umengChannel.isEmpty() || umengChannel.matches(Regex("[a-zA-Z0-9_-]{1,64}")))
require(!umengEnabled || (umengAppKey.isNotEmpty() && umengChannel.isNotEmpty())) {
    "Enabled Umeng analytics requires UMENG_APP_KEY and UMENG_CHANNEL."
}
fun buildString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val versionProperties = Properties().apply {
    rootProject.file("ci-version.properties").inputStream().use { load(it) }
}
val generatedVersion = Properties().apply {
    providers.environmentVariable("APP_BUILD_VERSION_FILE").orNull?.let { path ->
        rootProject.file(path).inputStream().use { load(it) }
    }
}
val appVersionName = generatedVersion.getProperty("versionName", rootProject.file("version.txt").readText().trim())
val appVersionCode = generatedVersion.getProperty("versionCode", versionProperties.getProperty("localVersionCode")).toInt()
val updateChannel = generatedVersion.getProperty("channel",
    providers.environmentVariable("UPDATE_CHANNEL").orElse("stable").get())
require(appVersionCode > 0 && appVersionName.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-beta(?:\\.[0-9]+)?)?")))
require(updateChannel in listOf("stable", "beta"))
android {
    namespace = "com.mercury.yktwx"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.mercury.yktwx"
        minSdk = 34
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PANGGUI_API_SECRET", "\"" + pangguiSecret.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        buildConfigField("String", "HUI798_API_GATEWAY", buildString(hui798Gateway))
        buildConfigField("String", "HUI798_SIGN_SALT", buildString(hui798Salt))
        buildConfigField("String", "UPDATE_PUBLIC_KEY", buildString(rootProject.file("updates/public-key.txt").readText().trim()))
        buildConfigField("String", "UPDATE_CHANNEL", buildString(updateChannel))
        manifestPlaceholders["updateChannel"] = updateChannel
        buildConfigField("String", "BUILD_COMMIT", buildString(generatedVersion.getProperty("commit", "local")))
        buildConfigField("boolean", "UMENG_ENABLED", "false")
        buildConfigField("String", "UMENG_APP_KEY", buildString(""))
        buildConfigField("String", "UMENG_CHANNEL", buildString(""))
    }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "UMENG_ENABLED", umengEnabled.toString())
            buildConfigField("String", "UMENG_APP_KEY", buildString(umengAppKey))
            buildConfigField("String", "UMENG_CHANNEL", buildString(umengChannel))
            manifestPlaceholders["umengScheme"] = "um.$umengAppKey"
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
    }
    sourceSets {
        // These builds share UI fixtures and a disabled analytics backend.
        listOf("debug", "preview").forEach {
            getByName(it).kotlin.directories.add("src/uiPreview/kotlin")
            getByName(it).kotlin.directories.add("src/uiTestHost/kotlin")
        }
        // Shared unit sources reference this host; release keeps it in tests only.
        maybeCreate("testRelease").kotlin.directories.add("src/uiTestHost/kotlin")
        getByName("release").kotlin.directories.add("src/production/kotlin")
        getByName("androidTest").assets.directories.add("src/test/resources/app-updates")
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
    if (name in setOf("testPreviewUnitTest", "testReleaseUnitTest")) {
        filter { includeTestsMatching("com.mercury.yktwx.UiPreviewIsolationTest") }
    }
}
java { toolchain { languageVersion = JavaLanguageVersion.of(22) } }
kotlin { jvmToolchain(22) }
tasks.withType<JavaCompile>().configureEach {
    // Hilt-generated Java must use the same compiler as application sources.
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion = JavaLanguageVersion.of(22)
    })
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.tooling)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.tink.android)
    implementation(libs.androidx.datastore)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.hilt.testing)
    kspTest(libs.hilt.compiler)
    implementation(libs.okhttp)
    "releaseImplementation"(libs.umeng.common)
    "releaseImplementation"(libs.umeng.asms)
    // Payment QR and barcode generation; camera decoding uses wechat-qrcode-lite.
    implementation(libs.zxing.core)
    implementation(files("libs/wechat-qrcode-lite-0.1.0-arm64-v8a.aar"))
    implementation(libs.alipay)
    implementation(files("libs/unionpay-sdk-mini-3.5.16.jar"))
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)
    implementation(libs.miuix.nav)
    implementation(libs.aboutlibraries.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
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

hilt { enableAggregatingTask = true }
