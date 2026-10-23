package dev.mobileshield.core.hygiene

enum class HygieneLevel { LOW, MEDIUM, HIGH, CRITICAL }

enum class HygieneCode {
    REMOTE_ACCESS_TOOL,
    ACCESSIBILITY_ENABLED,
    SIDELOADED_WITH_SMS,
    SIDELOADED_WITH_OVERLAY,
    SIDELOADED_DEVICE_ADMIN,
    SIDELOADED_INSTALLER,
    SIDELOADED_NOTIFICATION_LISTENER,
}

data class AppFinding(
    val packageId: String,
    val label: String,
    val code: HygieneCode,
    val severity: HygieneLevel,
    /** Points this finding contributes to the overall 0..100 score. */
    val weight: Int,
    /** Plain-language explanation safe to show the user. */
    val message: String,
)

data class HygieneReport(
    val level: HygieneLevel,
    val score: Int,
    val findings: List<AppFinding>,
) {
    /**
     * True when something could let an attacker watch or drive the screen right
     * now — the condition worth warning about before a UPI payment.
     */
    val hasRemoteControlRisk: Boolean
        get() = findings.any {
            it.code == HygieneCode.REMOTE_ACCESS_TOOL || it.code == HygieneCode.ACCESSIBILITY_ENABLED
        }
}

/**
 * Scores a snapshot of installed apps for the device-hygiene risks a safety app
 * can legitimately surface: screen-sharing/remote-access tools (the classic UPI
 * fraud setup), apps with an enabled accessibility service, and sideloaded apps
 * holding SMS / overlay / device-admin / notification-listener / installer
 * powers. It explains each risk and leaves the action to the user.
 *
 * Pure function of its input — no device access, no I/O — so it is unit-tested
 * exhaustively and reused unchanged on Android and iOS. System apps are skipped
 * to avoid flagging the OS's own components (e.g. TalkBack's accessibility
 * service).
 */
class DeviceHygieneAnalyzer(
    private val remoteAccessPackages: Set<String> = DEFAULT_REMOTE_ACCESS_PACKAGES,
) {
    fun analyze(apps: List<InstalledApp>): HygieneReport {
        val findings = ArrayList<AppFinding>()

        for (app in apps) {
            if (app.isSystem) continue
            val sideloaded =
                app.installSource == InstallSource.SIDELOADED || app.installSource == InstallSource.UNKNOWN

            if (app.packageId in remoteAccessPackages) {
                findings += finding(
                    app, HygieneCode.REMOTE_ACCESS_TOOL, HygieneLevel.CRITICAL, 60,
                    "'${app.label}' is a screen-sharing / remote-access app. Scammers ask you to " +
                        "install these so they can watch your screen during a UPI payment. Remove it " +
                        "unless you installed it yourself for a known reason.",
                )
            }
            if (app.accessibilityServiceEnabled) {
                findings += finding(
                    app, HygieneCode.ACCESSIBILITY_ENABLED, HygieneLevel.HIGH, 40,
                    "'${app.label}' has an Accessibility service turned on, letting it read the screen " +
                        "and tap on your behalf. Keep this on only for apps you fully trust.",
                )
            }
            if (sideloaded && SensitivePermission.READ_SMS in app.permissions) {
                findings += finding(
                    app, HygieneCode.SIDELOADED_WITH_SMS, HygieneLevel.HIGH, 35,
                    "'${app.label}' was installed from outside an app store and can read your SMS, " +
                        "including bank OTPs.",
                )
            }
            if (sideloaded && SensitivePermission.DISPLAY_OVER_OTHER_APPS in app.permissions) {
                findings += finding(
                    app, HygieneCode.SIDELOADED_WITH_OVERLAY, HygieneLevel.HIGH, 30,
                    "'${app.label}' was sideloaded and can draw over other apps, which can fake login " +
                        "or payment screens.",
                )
            }
            if (sideloaded && SensitivePermission.DEVICE_ADMIN in app.permissions) {
                findings += finding(
                    app, HygieneCode.SIDELOADED_DEVICE_ADMIN, HygieneLevel.HIGH, 30,
                    "'${app.label}' was sideloaded and holds Device Admin, making it hard to uninstall " +
                        "and able to lock your device.",
                )
            }
            if (sideloaded && SensitivePermission.NOTIFICATION_LISTENER in app.permissions) {
                findings += finding(
                    app, HygieneCode.SIDELOADED_NOTIFICATION_LISTENER, HygieneLevel.MEDIUM, 20,
                    "'${app.label}' was sideloaded and can read your notifications, including OTP and " +
                        "payment alerts.",
                )
            }
            if (sideloaded && SensitivePermission.INSTALL_UNKNOWN_APPS in app.permissions) {
                findings += finding(
                    app, HygieneCode.SIDELOADED_INSTALLER, HygieneLevel.MEDIUM, 20,
                    "'${app.label}' was sideloaded and can install other apps, a common way more " +
                        "malware gets on a phone.",
                )
            }
        }

        val score = findings.sumOf { it.weight }.coerceIn(0, 100)
        return HygieneReport(levelFor(score), score, findings.sortedByDescending { it.weight })
    }

    private fun finding(
        app: InstalledApp,
        code: HygieneCode,
        severity: HygieneLevel,
        weight: Int,
        message: String,
    ) = AppFinding(app.packageId, app.label, code, severity, weight, message)

    private fun levelFor(score: Int): HygieneLevel = when {
        score >= 60 -> HygieneLevel.CRITICAL
        score >= 35 -> HygieneLevel.HIGH
        score >= 15 -> HygieneLevel.MEDIUM
        else -> HygieneLevel.LOW
    }

    companion object {
        /** Package ids of common screen-sharing / remote-access apps. */
        val DEFAULT_REMOTE_ACCESS_PACKAGES: Set<String> = setOf(
            "com.anydesk.anydeskandroid",
            "com.teamviewer.teamviewer.market.mobile",
            "com.teamviewer.quicksupport.market",
            "com.sand.airdroid",
            "com.rsupport.rs.activity.rsupport.aas2",
            "com.microsoft.rdc.androidx",
            "com.google.chromeremotedesktop",
            "com.realvnc.viewer.android",
            "com.logmein.ignition",
            "com.splashtop.remote.pad.v2",
        )
    }
}
