plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(21)

    // JVM target: consumed by the Android app module, and the fast unit-test
    // surface used in CI on any host (Linux, macOS, Windows).
    jvm()

    // Apple targets for the iOS app's shared framework. The Kotlin/Native
    // toolchain for Apple targets only runs on a macOS host, so these are
    // declared only there. On Linux/CI the shared code is still fully exercised
    // through the JVM target and commonTest; the iOS build happens on a Mac.
    // All shared code lives in commonMain using only multiplatform Kotlin APIs,
    // so it compiles unchanged for both the JVM and iOS.
    if (org.jetbrains.kotlin.konan.target.HostManager.hostIsMac) {
        iosX64()
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
