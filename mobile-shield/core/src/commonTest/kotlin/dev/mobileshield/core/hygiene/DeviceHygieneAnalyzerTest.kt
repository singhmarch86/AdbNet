package dev.mobileshield.core.hygiene

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceHygieneAnalyzerTest {
    private val analyzer = DeviceHygieneAnalyzer()

    private fun app(
        id: String = "com.example.app",
        label: String = "Example",
        source: InstallSource = InstallSource.PLAY_STORE,
        perms: Set<SensitivePermission> = emptySet(),
        a11y: Boolean = false,
        system: Boolean = false,
    ) = InstalledApp(id, label, source, perms, a11y, system)

    @Test
    fun `clean device is low risk with no findings`() {
        val report = analyzer.analyze(
            listOf(
                app(id = "com.bank.app", label = "Bank"),
                app(id = "com.chat.app", label = "Chat", perms = setOf(SensitivePermission.READ_SMS)),
            )
        )
        assertEquals(HygieneLevel.LOW, report.level)
        assertTrue(report.findings.isEmpty())
        assertFalse(report.hasRemoteControlRisk)
    }

    @Test
    fun `remote access tool is critical and flags remote-control risk`() {
        val report = analyzer.analyze(
            listOf(app(id = "com.anydesk.anydeskandroid", label = "AnyDesk"))
        )
        assertEquals(HygieneLevel.CRITICAL, report.level)
        assertTrue(report.findings.any { it.code == HygieneCode.REMOTE_ACCESS_TOOL })
        assertTrue(report.hasRemoteControlRisk)
    }

    @Test
    fun `enabled accessibility service is high and flags remote-control risk`() {
        val report = analyzer.analyze(listOf(app(label = "Macro", a11y = true)))
        assertEquals(HygieneLevel.HIGH, report.level)
        assertTrue(report.findings.any { it.code == HygieneCode.ACCESSIBILITY_ENABLED })
        assertTrue(report.hasRemoteControlRisk)
    }

    @Test
    fun `sideloaded app with sms is flagged but play-store app is not`() {
        val sideloaded = analyzer.analyze(
            listOf(app(source = InstallSource.SIDELOADED, perms = setOf(SensitivePermission.READ_SMS)))
        )
        assertTrue(sideloaded.findings.any { it.code == HygieneCode.SIDELOADED_WITH_SMS })
        assertEquals(HygieneLevel.HIGH, sideloaded.level)

        val fromStore = analyzer.analyze(
            listOf(app(source = InstallSource.PLAY_STORE, perms = setOf(SensitivePermission.READ_SMS)))
        )
        assertTrue(fromStore.findings.isEmpty())
    }

    @Test
    fun `unknown source is treated like sideloaded`() {
        val report = analyzer.analyze(
            listOf(app(source = InstallSource.UNKNOWN, perms = setOf(SensitivePermission.DISPLAY_OVER_OTHER_APPS)))
        )
        assertTrue(report.findings.any { it.code == HygieneCode.SIDELOADED_WITH_OVERLAY })
    }

    @Test
    fun `system apps are skipped even with sensitive powers`() {
        val report = analyzer.analyze(
            listOf(
                app(
                    id = "com.android.systemui",
                    label = "System UI",
                    source = InstallSource.PREINSTALLED,
                    perms = setOf(SensitivePermission.DISPLAY_OVER_OTHER_APPS),
                    a11y = true,
                    system = true,
                )
            )
        )
        assertTrue(report.findings.isEmpty())
        assertEquals(HygieneLevel.LOW, report.level)
    }

    @Test
    fun `multiple findings accumulate and are capped and sorted`() {
        val report = analyzer.analyze(
            listOf(
                app(id = "com.anydesk.anydeskandroid", label = "AnyDesk"), // 60
                app(
                    id = "com.bad.app",
                    label = "Bad",
                    source = InstallSource.SIDELOADED,
                    perms = setOf(
                        SensitivePermission.READ_SMS,                 // 35
                        SensitivePermission.NOTIFICATION_LISTENER,    // 20
                    ),
                    a11y = true,                                      // 40
                ),
            )
        )
        assertEquals(100, report.score) // 60+40+35+20 capped at 100
        assertEquals(HygieneLevel.CRITICAL, report.level)
        // Sorted by weight descending: remote-access (60) first.
        assertEquals(HygieneCode.REMOTE_ACCESS_TOOL, report.findings.first().code)
    }

    @Test
    fun `single medium finding yields medium level`() {
        val report = analyzer.analyze(
            listOf(app(source = InstallSource.SIDELOADED, perms = setOf(SensitivePermission.INSTALL_UNKNOWN_APPS)))
        )
        assertEquals(HygieneLevel.MEDIUM, report.level)
        assertTrue(report.findings.any { it.code == HygieneCode.SIDELOADED_INSTALLER })
    }
}
