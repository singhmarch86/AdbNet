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

// The Android app builds only when you opt in with mobileshield.android=true —
// as a -P flag, or in gradle.properties (e.g. ~/.gradle/gradle.properties so
// Android Studio includes it too). It needs the Android Gradle Plugin + SDK
// from Google, which some sandboxed/CI environments block; the same flag loads
// AGP into the root classloader in build.gradle.kts.
//   CLI:    ./gradlew :androidApp:assembleDebug -Pmobileshield.android=true
//   Studio: add  mobileshield.android=true  to ~/.gradle/gradle.properties
val androidEnabled = providers.gradleProperty("mobileshield.android").orNull == "true" ||
    startParameter.projectProperties["mobileshield.android"] == "true"
if (androidEnabled) {
    include(":androidApp")
}
