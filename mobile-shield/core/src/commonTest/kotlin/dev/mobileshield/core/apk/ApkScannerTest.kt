package dev.mobileshield.core.apk

import dev.mobileshield.core.hygiene.SensitivePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApkScannerTest {
    private val scanner = ApkScanner()

    @Test
    fun `non-apk file is ignored`() {
        val r = scanner.scan(DownloadedApk(fileName = "photo.jpg", sizeBytes = 2_000_000))
        assertFalse(r.isApk)
        assertEquals(ApkRiskLevel.LOW, r.level)
        assertTrue(r.findings.isEmpty())
    }

    @Test
    fun `plain signed apk with no red flags is low risk`() {
        val r = scanner.scan(
            DownloadedApk(
                fileName = "mygame.apk",
                sizeBytes = 8_000_000,
                apkInfo = ApkInfo(packageId = "com.studio.mygame", appLabel = "My Game"),
            )
        )
        assertTrue(r.isApk)
        assertEquals(ApkRiskLevel.LOW, r.level)
        assertTrue(r.findings.isEmpty())
    }

    @Test
    fun `disguised document extension is flagged`() {
        val r = scanner.scan(DownloadedApk(fileName = "invoice.pdf.apk", sizeBytes = 5_000_000))
        assertTrue(r.findings.any { it.code == ApkRiskCode.DISGUISED_EXTENSION })
        assertTrue(r.level == ApkRiskLevel.HIGH || r.level == ApkRiskLevel.CRITICAL)
    }

    @Test
    fun `apk with trailing browser suffix still detected and decoy still seen`() {
        val r = scanner.scan(DownloadedApk(fileName = "statement.pdf.apk.1", sizeBytes = 5_000_000))
        assertTrue(r.isApk)
        assertTrue(r.findings.any { it.code == ApkRiskCode.DISGUISED_EXTENSION })
    }

    @Test
    fun `unsigned apk is flagged`() {
        val r = scanner.scan(
            DownloadedApk(
                fileName = "tool.apk",
                sizeBytes = 2_000_000,
                apkInfo = ApkInfo(packageId = "x.y.z", isSigned = false),
            )
        )
        assertTrue(r.findings.any { it.code == ApkRiskCode.UNSIGNED_APK })
    }

    @Test
    fun `dangerous permissions are flagged`() {
        val r = scanner.scan(
            DownloadedApk(
                fileName = "helper.apk",
                sizeBytes = 2_000_000,
                apkInfo = ApkInfo(
                    packageId = "x.y.z",
                    requestedPermissions = setOf(SensitivePermission.READ_SMS, SensitivePermission.ACCESSIBILITY),
                ),
            )
        )
        assertTrue(r.findings.any { it.code == ApkRiskCode.DANGEROUS_PERMISSIONS })
    }

    @Test
    fun `tiny apk looks like a dropper`() {
        val r = scanner.scan(DownloadedApk(fileName = "update.apk", sizeBytes = 12_000))
        assertTrue(r.findings.any { it.code == ApkRiskCode.TINY_DROPPER })
    }

    @Test
    fun `media mime on an apk is a mismatch`() {
        val r = scanner.scan(
            DownloadedApk(fileName = "clip.apk", sizeBytes = 3_000_000, mimeType = "video/mp4")
        )
        assertTrue(r.findings.any { it.code == ApkRiskCode.MIME_MISMATCH })
    }

    @Test
    fun `brand keyword triggers impersonation warning`() {
        val r = scanner.scan(
            DownloadedApk(
                fileName = "sbi_bank_update.apk",
                sizeBytes = 4_000_000,
                apkInfo = ApkInfo(packageId = "com.unknown.app", appLabel = "SBI Secure"),
            )
        )
        assertTrue(r.findings.any { it.code == ApkRiskCode.BRAND_IMPERSONATION })
    }

    @Test
    fun `unknown source host is flagged but trusted host is not`() {
        val untrusted = scanner.scan(
            DownloadedApk(fileName = "app.apk", sizeBytes = 4_000_000, sourceHost = "free-apps.example")
        )
        assertTrue(untrusted.findings.any { it.code == ApkRiskCode.UNKNOWN_SOURCE })

        val trusted = scanner.scan(
            DownloadedApk(fileName = "app.apk", sizeBytes = 4_000_000, sourceHost = "m.apkmirror.com")
        )
        assertFalse(trusted.findings.any { it.code == ApkRiskCode.UNKNOWN_SOURCE })
    }

    @Test
    fun `stacked signals reach critical and cap at 100`() {
        val r = scanner.scan(
            DownloadedApk(
                fileName = "bank_statement.pdf.apk",      // disguised(50) + brand(30)
                sizeBytes = 10_000,                        // tiny(20)
                sourceHost = "scam.example",               // unknown(10)
                mimeType = "application/pdf",              // mismatch(15)
                apkInfo = ApkInfo(
                    packageId = "a.b.c",
                    appLabel = "Bank KYC",
                    isSigned = false,                      // unsigned(30)
                    requestedPermissions = setOf(SensitivePermission.READ_SMS), // dangerous(25)
                ),
            )
        )
        assertEquals(ApkRiskLevel.CRITICAL, r.level)
        assertEquals(100, r.score)
    }

    @Test
    fun `scanAll maps each file`() {
        val reports = scanner.scanAll(
            listOf(
                DownloadedApk(fileName = "a.apk", sizeBytes = 5_000_000),
                DownloadedApk(fileName = "readme.txt", sizeBytes = 1000),
            )
        )
        assertEquals(2, reports.size)
        assertTrue(reports[0].isApk)
        assertFalse(reports[1].isApk)
    }
}
