buildscript {
    dependencies { classpath(libs.kotlin.gradle.plugin) }
}
plugins { alias(libs.plugins.android.application) apply false }
