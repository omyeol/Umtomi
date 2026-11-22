plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.gms.oss.licenses)
    id("kotlin-parcelize")
}

android {
    namespace = "xyz.sabok.umtomi"
    compileSdk = 35

    defaultConfig {
        applicationId = "xyz.sabok.umtomi"
        minSdk = 21
        targetSdk = 35
        versionCode = 106
        versionName = "5.15.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false

            isDebuggable = true

            extra.apply {
                set("enableCrashlytics", false)
                set("alwaysUpdateBuildId", false)
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        viewBinding = true
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation("androidx.exifinterface:exifinterface:1.3.6")
    implementation(libs.kotlin.stdlib.jdk8)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    implementation(libs.androidx.compose.runtime)

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation(libs.constraintlayout)
    implementation(libs.gridlayout)
    implementation(libs.biometric)
    implementation(libs.work.runtime.ktx)

    implementation(libs.library)

    implementation(libs.material)


    implementation(libs.play.services.oss.licenses)
    implementation(libs.play.services.mlkit.face.detection)

    implementation(libs.fab)

    implementation(libs.bigimageviewer)
    implementation(libs.frescoimageloader)
    implementation(libs.frescoimageviewfactory)
    implementation(libs.imagepipeline.okhttp3)

    //noinspection GradleDependency
    implementation(libs.okhttp)
    implementation(libs.ktor.network)

    implementation(libs.dotsindicator)

    implementation(libs.pinlockview)
    implementation(libs.patternlockview)

    implementation(libs.core)

    implementation(libs.ripplebackground.library)
    implementation(libs.recyclerview.fastscroller)

    implementation(libs.jsoup)

    implementation(libs.documentfilex)
    implementation(libs.floatingsearchview)

    testImplementation("org.json:json:20260814")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.rules)
    androidTestImplementation(libs.runner)
    androidTestImplementation(libs.espresso.core)
}



