buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
    dependencies {
        // Kotlin is needed by :core on every build. It resolves from Maven
        // Central, so a core-only build works where Google's Maven is blocked.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")

        // AGP + the Compose compiler are only needed for the Android app.
        // Loading them here, into the shared root classloader (rather than via
        // per-module pluginManagement), is what lets the Kotlin Android plugin
        // see AGP's classes — otherwise they land in separate classloaders and
        // KotlinAndroidTarget fails with NoClassDefFoundError: BaseVariant.
        // Gated on the opt-in flag so a core-only build never fetches them.
        if (project.hasProperty("mobileshield.android")) {
            classpath("com.android.tools.build:gradle:8.5.2")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
        }
    }
}

allprojects {
    repositories {
        mavenCentral()
        google()
    }
}
