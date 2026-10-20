plugins {
    id("com.android.application")
    id("com.mikepenz.aboutlibraries.plugin.android") version "15.2.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}
android {
    namespace = "com.mercury.yktwx"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.mercury.yktwx"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true; unitTests.isIncludeAndroidResources = true }
    buildTypes {
        create("preview") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".preview"
            resValue("string", "app_name", "YktWx Preview")
            matchingFallbacks += "debug"
        }
        create("personal") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
}
java { toolchain { languageVersion = JavaLanguageVersion.of(22) } }
kotlin { jvmToolchain(22) }
dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.zxing:core:3.5.4")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4-rc01")
    implementation("com.mikepenz:aboutlibraries-core:15.2.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

aboutLibraries {
    offlineMode = true
    collect { includePlatform = false }
    export { prettyPrint = true }
}
