package dev.mobileshield.android.guard

/**
 * Shared, lock-free state written by whichever live source is active (the
 * UsageStats poller in [PaymentGuardService], and the optional
 * [ShieldAccessibilityService]) and read by the guard loop. Volatile fields are
 * enough: single writer per field, single reader, no compound updates.
 */
object LiveGuardState {
    /** Package currently in the foreground, best-effort. */
    @Volatile
    var foregroundPackage: String? = null

    /**
     * A remote-access / screen-sharing app window is currently active. Only the
     * accessibility service can set this reliably; without it the guard falls
     * back to "a remote-access app was recently in the foreground".
     */
    @Volatile
    var remoteAccessWindowActive: Boolean = false

    /** The accessibility service saw an overlay window drawn over another app. */
    @Volatile
    var overlayDetected: Boolean = false

    /** True while the opt-in accessibility service is connected. */
    @Volatile
    var accessibilityConnected: Boolean = false
}
