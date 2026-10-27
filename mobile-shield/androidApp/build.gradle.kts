// Android application module for mobile-shield.
//
// This module is NOT part of the default Gradle build — see the commented
// `include(":androidApp")` in ../settings.gradle.kts for why (its AGP + SDK
// dependencies come from dl.google.com, which restricted sandboxes block).
//
// To build it on a host with Google Maven + the Android SDK:
//   1. Uncomment `include(":androidApp")` in settings.gradle.kts
//   2. Point ANDROID_HOME / local.properties at your SDK
//   3. ./gradlew :androidApp:assembleDebug -Pmobileshield.android=true
// The -P flag turns on the shared :core module's Android target.

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.mobileshield.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.mobileshield.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // QR camera scanner: CameraX preview/analysis + ML Kit barcode decoding.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
