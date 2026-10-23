pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google() // last: only queried for Android plugins, which live here
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "mobile-shield"

include(":core")

// The Android app lives in androidApp/ as source. It is intentionally left out
// of the build by default because its Android Gradle Plugin + SDK dependencies
// are served from dl.google.com, which some sandboxed/CI environments block.
// To build the Android app on a host with access to Google's Maven + the
// Android SDK, uncomment the line below and build with:
//   ./gradlew :androidApp:assembleDebug -Pmobileshield.android=true
// (The -P flag switches on the shared core's androidTarget; see core/build.gradle.kts.)
//
// include(":androidApp")
