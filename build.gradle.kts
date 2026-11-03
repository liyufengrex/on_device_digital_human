plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

buildscript {
    dependencies {
        // AGP 9 ships an older Kotlin Gradle plugin. Pin the same version as the Compose compiler.
        classpath(libs.kotlin.gradle.plugin)
    }
}
