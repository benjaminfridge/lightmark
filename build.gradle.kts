plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

group = "com.thelightphone"

// Mirrors light-sdk/build.gradle.kts at the pinned tag: the SDK modules read these
// from the root project, so they have to exist here too.
ext["compileSdk"] = 36
ext["minSdk"] = 34
ext["targetSdk"] = 34
ext["jvmTarget"] = "17"
ext["lintVersion"] = "31.12.3"
