// Root build file for the Helium Android application.
//
// AGP 9.x provides built-in Kotlin support, so modules must NOT apply the
// `org.jetbrains.kotlin.android` plugin. Applying `org.jetbrains.kotlin.plugin.compose`
// (+ the JVM/serialization plugins for pure-JVM modules) is all that is required.

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
