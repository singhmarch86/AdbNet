pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google() // Android plugins live here
    }
    // Versions for plugins applied by subprojects without a version. These are
    // lazy: a plugin is only resolved when a module actually applies it, so a
    // core-only build (without -Pmobileshield.android) never fetches the Android
    // plugins — which keeps the shared module buildable where Google's Maven is
    // blocked.
    plugins {
        id("com.android.application") version "8.5.2"
        id("com.android.library") version "8.5.2"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
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

// The Android app builds only when you opt in with -Pmobileshield.android=true
// (it needs the Android Gradle Plugin + SDK from Google, which some sandboxed /
// CI environments block). Build it with:
//   ./gradlew :androidApp:assembleDebug -Pmobileshield.android=true
if (startParameter.projectProperties["mobileshield.android"] == "true") {
    include(":androidApp")
}
