package dev.mobileshield.core.hygiene

/** Where an app came from — the single strongest signal of risk on Android. */
enum class InstallSource {
    PLAY_STORE,
    OTHER_APP_STORE,
    SIDELOADED,
    PREINSTALLED,
    UNKNOWN,
}

/**
 * Platform-neutral categories of sensitive capability. The Android shell maps
 * real permissions / settings onto these (e.g. `READ_SMS`, `SYSTEM_ALERT_WINDOW`,
 * an enabled accessibility service); iOS maps its own. Keeping the core in these
 * abstract terms is what lets the same analyzer serve both platforms.
 */
enum class SensitivePermission {
    READ_SMS,
    DISPLAY_OVER_OTHER_APPS,
    ACCESSIBILITY,
    DEVICE_ADMIN,
    INSTALL_UNKNOWN_APPS,
    NOTIFICATION_LISTENER,
}

/** A snapshot of one installed app, as gathered by the platform shell. */
data class InstalledApp(
    val packageId: String,
    val label: String,
    val installSource: InstallSource,
    val permissions: Set<SensitivePermission> = emptySet(),
    /** True when this app currently has an *enabled* accessibility service. */
    val accessibilityServiceEnabled: Boolean = false,
    /** True for OS/system apps, held to a lower suspicion bar to avoid noise. */
    val isSystem: Boolean = false,
)
