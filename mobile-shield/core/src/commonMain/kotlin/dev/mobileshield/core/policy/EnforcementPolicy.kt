package dev.mobileshield.core.policy

import dev.mobileshield.core.hygiene.HygieneLevel
import dev.mobileshield.core.hygiene.HygieneReport

/** How aggressively the device should be locked down. */
enum class ProtectionProfile {
    /** Adult's own device: advise, don't enforce. Respects autonomy. */
    STANDARD,

    /** A child's device (with their knowledge): lock the protections on. */
    CHILD,

    /** Fraud-targeted / managed device: maximum lockdown. */
    HIGH_SECURITY,
}

/**
 * Concrete lockdowns a Device Owner app can apply via `DevicePolicyManager`.
 * These are the OS-level enforcements that turn mobile-shield's "warn" into
 * "prevent" on a managed or child device.
 */
enum class EnforcementAction {
    FORCE_VPN_ALWAYS_ON,
    DISALLOW_INSTALL_UNKNOWN_SOURCES,
    BLOCK_ALL_APP_INSTALLS,
    LOCK_SAFESEARCH,
    DISALLOW_SCREEN_CAPTURE,
    DISALLOW_DEBUGGING_FEATURES,
    DISALLOW_SAFE_BOOT,
    REMOVE_REMOTE_ACCESS_APPS,
}

/** Inputs to the policy decision. */
data class PolicyContext(
    /** True when the app is provisioned as device owner (can actually enforce). */
    val isDeviceOwner: Boolean,
    val profile: ProtectionProfile,
    /** Latest device-hygiene result, used to escalate dynamically. */
    val hygiene: HygieneReport? = null,
)

data class EnforcementDecision(
    val action: EnforcementAction,
    /** True only when it can actually be applied (i.e. the app is device owner). */
    val enabled: Boolean,
    val rationale: String,
)

data class EnforcementPlan(
    val canEnforce: Boolean,
    val profile: ProtectionProfile,
    val decisions: List<EnforcementDecision>,
) {
    /** The actions that will actually be applied now. */
    val enabledActions: List<EnforcementAction> get() = decisions.filter { it.enabled }.map { it.action }
}

/**
 * Decides which OS-level enforcements to apply, from the chosen
 * [ProtectionProfile] plus live device risk. It never performs the actions —
 * the Android `DeviceAdminReceiver` / `DevicePolicyManager` layer does that when
 * the app is a device owner. Here it is pure, deterministic and unit-tested.
 *
 * When the app is NOT a device owner, every action comes back `enabled = false`
 * with a rationale noting it degrades to a warning — so the UI can honestly say
 * what it could enforce in a managed/child setup versus what it can only warn
 * about today.
 */
class EnforcementPolicyEngine {

    fun plan(context: PolicyContext): EnforcementPlan {
        val desired = LinkedHashMap<EnforcementAction, String>()

        if (context.profile == ProtectionProfile.CHILD || context.profile == ProtectionProfile.HIGH_SECURITY) {
            desired[EnforcementAction.FORCE_VPN_ALWAYS_ON] =
                "Keep the content filter always on so it cannot be turned off."
            desired[EnforcementAction.DISALLOW_INSTALL_UNKNOWN_SOURCES] =
                "Block installing apps from outside an app store (sideloading)."
            desired[EnforcementAction.LOCK_SAFESEARCH] =
                "Keep SafeSearch locked on."
            desired[EnforcementAction.DISALLOW_DEBUGGING_FEATURES] =
                "Turn off USB/ADB debugging, a common attack entry point."
        }
        if (context.profile == ProtectionProfile.HIGH_SECURITY) {
            desired[EnforcementAction.DISALLOW_SCREEN_CAPTURE] =
                "Block screen capture/recording to stop screen-sharing scams."
            desired[EnforcementAction.DISALLOW_SAFE_BOOT] =
                "Prevent booting into safe mode to bypass protections."
        }

        // Dynamic escalation from current device risk.
        val hygiene = context.hygiene
        if (hygiene != null && hygiene.hasRemoteControlRisk) {
            desired[EnforcementAction.REMOVE_REMOTE_ACCESS_APPS] =
                "A remote-access / screen-sharing app or accessibility service is active; removing it " +
                    "closes the main remote-control fraud channel."
            if (context.profile != ProtectionProfile.STANDARD) {
                desired.putIfAbsent(
                    EnforcementAction.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                    "Block sideloading while remote-control risk is present.",
                )
            }
        }
        if (hygiene != null &&
            (hygiene.level == HygieneLevel.HIGH || hygiene.level == HygieneLevel.CRITICAL) &&
            context.profile == ProtectionProfile.HIGH_SECURITY
        ) {
            desired[EnforcementAction.BLOCK_ALL_APP_INSTALLS] =
                "Device risk is high; block all new app installs until it is resolved."
        }

        val decisions = desired.map { (action, why) ->
            EnforcementDecision(
                action = action,
                enabled = context.isDeviceOwner,
                rationale = if (context.isDeviceOwner) {
                    why
                } else {
                    "$why (Requires Mobile Shield to be set as device owner in a managed/child setup; " +
                        "without it this can only be a warning.)"
                },
            )
        }

        return EnforcementPlan(
            canEnforce = context.isDeviceOwner,
            profile = context.profile,
            decisions = decisions,
        )
    }
}
