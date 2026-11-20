// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
    alias(libs.plugins.gms.oss.licenses) apply false
}

// Keep intermediate files outside OneDrive when requested by the Windows build script.
allprojects {
    providers.gradleProperty("umtomiBuildDir").orNull?.let { directory ->
        layout.buildDirectory.set(file("$directory/${project.name}"))
    }
}
