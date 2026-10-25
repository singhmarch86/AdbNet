package dev.mobileshield.core.policy

import dev.mobileshield.core.hygiene.DeviceHygieneAnalyzer
import dev.mobileshield.core.hygiene.InstallSource
import dev.mobileshield.core.hygiene.InstalledApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnforcementPolicyEngineTest {
    private val engine = EnforcementPolicyEngine()
    private val hygiene = DeviceHygieneAnalyzer()

    private fun deviceWithRemoteAccess() =
        hygiene.analyze(listOf(InstalledApp("com.anydesk.anydeskandroid", "AnyDesk", InstallSource.PLAY_STORE)))

    @Test
    fun `standard profile enforces nothing by default`() {
        val plan = engine.plan(PolicyContext(isDeviceOwner = true, profile = ProtectionProfile.STANDARD))
        assertTrue(plan.canEnforce)
        assertTrue(plan.enabledActions.isEmpty())
    }

    @Test
    fun `child profile without device owner can only warn`() {
        val plan = engine.plan(PolicyContext(isDeviceOwner = false, profile = ProtectionProfile.CHILD))
        assertFalse(plan.canEnforce)
        assertTrue(plan.decisions.isNotEmpty())
        assertTrue(plan.decisions.all { !it.enabled })
        assertTrue(plan.decisions.all { it.rationale.contains("device owner", ignoreCase = true) })
        assertTrue(plan.enabledActions.isEmpty())
    }

    @Test
    fun `child profile as device owner locks the core protections`() {
        val plan = engine.plan(PolicyContext(isDeviceOwner = true, profile = ProtectionProfile.CHILD))
        assertTrue(plan.canEnforce)
        val actions = plan.enabledActions
        assertTrue(EnforcementAction.FORCE_VPN_ALWAYS_ON in actions)
        assertTrue(EnforcementAction.DISALLOW_INSTALL_UNKNOWN_SOURCES in actions)
        assertTrue(EnforcementAction.LOCK_SAFESEARCH in actions)
        assertTrue(EnforcementAction.DISALLOW_DEBUGGING_FEATURES in actions)
    }

    @Test
    fun `high security adds screen-capture and safe-boot lockdowns`() {
        val plan = engine.plan(PolicyContext(isDeviceOwner = true, profile = ProtectionProfile.HIGH_SECURITY))
        val actions = plan.enabledActions
        assertTrue(EnforcementAction.DISALLOW_SCREEN_CAPTURE in actions)
        assertTrue(EnforcementAction.DISALLOW_SAFE_BOOT in actions)
    }

    @Test
    fun `remote-access risk adds removal action`() {
        val plan = engine.plan(
            PolicyContext(
                isDeviceOwner = true,
                profile = ProtectionProfile.CHILD,
                hygiene = deviceWithRemoteAccess(),
            )
        )
        assertTrue(EnforcementAction.REMOVE_REMOTE_ACCESS_APPS in plan.enabledActions)
    }

    @Test
    fun `high risk on high-security device blocks all installs`() {
        val plan = engine.plan(
            PolicyContext(
                isDeviceOwner = true,
                profile = ProtectionProfile.HIGH_SECURITY,
                hygiene = deviceWithRemoteAccess(), // CRITICAL
            )
        )
        assertTrue(EnforcementAction.BLOCK_ALL_APP_INSTALLS in plan.enabledActions)
    }

    @Test
    fun `standard profile does not block installs even under risk`() {
        val plan = engine.plan(
            PolicyContext(
                isDeviceOwner = true,
                profile = ProtectionProfile.STANDARD,
                hygiene = deviceWithRemoteAccess(),
            )
        )
        // Standard still advises removing remote access, but never blocks installs.
        assertFalse(EnforcementAction.BLOCK_ALL_APP_INSTALLS in plan.enabledActions)
        assertTrue(EnforcementAction.REMOVE_REMOTE_ACCESS_APPS in plan.enabledActions)
    }
}
