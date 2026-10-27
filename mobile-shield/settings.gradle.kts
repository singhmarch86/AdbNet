pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
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
// CI environments block). The same flag loads AGP into the root classloader in
// build.gradle.kts. Build it with:
//   ./gradlew :androidApp:assembleDebug -Pmobileshield.android=true
if (startParameter.projectProperties["mobileshield.android"] == "true") {
    include(":androidApp")
}
