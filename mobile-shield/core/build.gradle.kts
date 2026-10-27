import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    // JVM target: consumed by the Android app module (which compiles at 17, so
    // bytecode is pinned to 17) and the CI unit-test surface that runs on any
    // host. The Android app depends on this JVM variant directly, so `core`
    // itself needs no Android Gradle Plugin — which keeps it buildable where
    // Google's Maven is blocked, and avoids a second AGP classloader clashing
    // with the app's.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Apple targets for the iOS app's shared framework (macOS host only). All
    // shared code is in commonMain using only multiplatform Kotlin APIs, so it
    // compiles unchanged for the JVM and iOS.
    if (org.jetbrains.kotlin.konan.target.HostManager.hostIsMac) {
        listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
            target.binaries.framework {
                baseName = "MobileShieldCore"
                isStatic = true
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
