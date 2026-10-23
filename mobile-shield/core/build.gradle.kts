import org.gradle.kotlin.dsl.withGroovyBuilder
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

// The shared module targets Android too, but the Android Gradle Plugin and SDK
// are served from dl.google.com, which some sandboxed/CI environments block.
// So the Android target is opt-in: pass -Pmobileshield.android=true on a host
// that can reach Google's Maven + the Android SDK. Without the flag (the
// default, including this repo's CI), `core` is a plain JVM + iOS module whose
// logic is still fully exercised by `:core:jvmTest`.
val withAndroid = (findProperty("mobileshield.android") as String?)?.toBoolean() == true

buildscript {
    if (project.hasProperty("mobileshield.android")) {
        repositories {
            google()
            mavenCentral()
        }
        dependencies {
            classpath("com.android.tools.build:gradle:8.7.3")
        }
    }
}

if (withAndroid) {
    apply(plugin = "com.android.library")
    // Configured without AGP type references so this script still compiles when
    // the Android plugin is absent (the default in restricted environments).
    extensions.getByName("android").withGroovyBuilder {
        setProperty("namespace", "dev.mobileshield.core")
        setProperty("compileSdk", 34)
        "defaultConfig" {
            setProperty("minSdk", 26)
        }
    }
}

kotlin {
    // JVM target: consumed by the Android app module (and the fast CI unit-test
    // surface that runs on any host — Linux, macOS, Windows). Bytecode is
    // pinned to Java 17 so the Android app (which compiles at 17) can consume
    // it; the compiler itself runs on whatever JDK (>=17) Gradle is using.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // Android target (opt-in, see above): lets the Android app consume the
    // shared code as a first-class androidJvm variant.
    if (withAndroid) {
        androidTarget()
    }

    // Apple targets for the iOS app's shared framework. The Kotlin/Native
    // toolchain for Apple targets only runs on a macOS host, so these are
    // declared only there. On Linux/CI the shared code is still fully exercised
    // through the JVM target and commonTest; the iOS build happens on a Mac.
    // All shared code lives in commonMain using only multiplatform Kotlin APIs,
    // so it compiles unchanged for the JVM, Android, and iOS.
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
