package dev.mobileshield.android.policy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.UserManager
import dev.mobileshield.core.hygiene.DeviceHygieneAnalyzer
import dev.mobileshield.core.policy.EnforcementAction
import dev.mobileshield.core.policy.EnforcementPlan

/**
 * Executes a core [EnforcementPlan] via `DevicePolicyManager` when the app is
 * device owner. Each core action maps to a concrete OS-level restriction. When
 * the app is not device owner, nothing is applied (the plan's actions already
 * carry "warning only" rationales for the UI).
 *
 * This is the layer that turns mobile-shield from "warn" into "prevent" on a
 * managed or child device.
 */
class PolicyEnforcer(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ShieldDeviceAdminReceiver.componentName(context)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    /** Applies every enabled action in [plan]; returns a per-action result log. */
    fun apply(plan: EnforcementPlan): List<String> {
        if (!isDeviceOwner()) {
            return listOf("Not device owner — no enforcement applied. These remain warnings.")
        }
        val log = mutableListOf<String>()
        for (action in plan.enabledActions) {
            runCatching { applyOne(action, log) }
                .onSuccess { log.add("✓ ${label(action)}") }
                .onFailure { log.add("✗ ${label(action)} — ${it.message}") }
        }
        return log
    }

    private fun applyOne(action: EnforcementAction, log: MutableList<String>) {
        when (action) {
            // SafeSearch is enforced through the always-on filtering VPN, so both
            // map to pinning our VpnService on with lockdown.
            EnforcementAction.FORCE_VPN_ALWAYS_ON,
            EnforcementAction.LOCK_SAFESEARCH -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    dpm.setAlwaysOnVpnPackage(admin, context.packageName, true)
                }
            }
            EnforcementAction.DISALLOW_INSTALL_UNKNOWN_SOURCES ->
                dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            EnforcementAction.BLOCK_ALL_APP_INSTALLS ->
                dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            EnforcementAction.DISALLOW_SCREEN_CAPTURE ->
                dpm.setScreenCaptureDisabled(admin, true)
            EnforcementAction.DISALLOW_DEBUGGING_FEATURES ->
                dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
            EnforcementAction.DISALLOW_SAFE_BOOT ->
                dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            EnforcementAction.REMOVE_REMOTE_ACCESS_APPS ->
                suspendRemoteAccessApps(log)
        }
    }

    @Suppress("DEPRECATION")
    private fun suspendRemoteAccessApps(log: MutableList<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val present = context.packageManager.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it in DeviceHygieneAnalyzer.DEFAULT_REMOTE_ACCESS_PACKAGES }
        if (present.isNotEmpty()) {
            dpm.setPackagesSuspended(admin, present.toTypedArray(), true)
            log.add("  suspended: ${present.joinToString()}")
        }
    }

    /** Lifts every restriction this enforcer can set (e.g. profile downgrade). */
    fun clearAll(): Boolean {
        if (!isDeviceOwner()) return false
        runCatching {
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
            dpm.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)
            dpm.setScreenCaptureDisabled(admin, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setAlwaysOnVpnPackage(admin, null, false)
            }
        }
        return true
    }

    private fun label(action: EnforcementAction): String = when (action) {
        EnforcementAction.FORCE_VPN_ALWAYS_ON -> "Content filter locked on"
        EnforcementAction.LOCK_SAFESEARCH -> "SafeSearch locked (via filter)"
        EnforcementAction.DISALLOW_INSTALL_UNKNOWN_SOURCES -> "Sideloading blocked"
        EnforcementAction.BLOCK_ALL_APP_INSTALLS -> "All app installs blocked"
        EnforcementAction.DISALLOW_SCREEN_CAPTURE -> "Screen capture blocked"
        EnforcementAction.DISALLOW_DEBUGGING_FEATURES -> "USB/ADB debugging blocked"
        EnforcementAction.DISALLOW_SAFE_BOOT -> "Safe boot blocked"
        EnforcementAction.REMOVE_REMOTE_ACCESS_APPS -> "Remote-access apps suspended"
    }
}
