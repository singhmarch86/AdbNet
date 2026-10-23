package dev.mobileshield.core.apk

import dev.mobileshield.core.hygiene.SensitivePermission

enum class ApkRiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

enum class ApkRiskCode {
    NOT_AN_APK,
    DISGUISED_EXTENSION,
    UNSIGNED_APK,
    DANGEROUS_PERMISSIONS,
    TINY_DROPPER,
    MIME_MISMATCH,
    BRAND_IMPERSONATION,
    UNKNOWN_SOURCE,
}

data class ApkFinding(
    val code: ApkRiskCode,
    val weight: Int,
    val message: String,
)

data class ApkRiskReport(
    val fileName: String,
    val isApk: Boolean,
    val level: ApkRiskLevel,
    val score: Int,
    val findings: List<ApkFinding>,
)

/**
 * Warns about APK files sitting in Downloads *before* the user sideloads them —
 * the moment that precedes most Android malware installs. It judges only the
 * metadata the platform can read cheaply (file name, size, source, MIME) plus
 * manifest facts when the platform parsed the package; it does not itself read
 * or execute the APK.
 *
 * Pure and testable; the thresholds and keyword/host sets are constructor
 * parameters so they can be tuned. This feeds the same "should I trust this?"
 * decision the device-hygiene module supports, but at download time.
 */
class ApkScanner(
    private val tinyApkBytes: Long = 30_000,
    private val brandKeywords: Set<String> = DEFAULT_BRAND_KEYWORDS,
    private val trustedSourceHosts: Set<String> = DEFAULT_TRUSTED_SOURCE_HOSTS,
) {
    fun scanAll(files: List<DownloadedApk>): List<ApkRiskReport> = files.map { scan(it) }

    fun scan(apk: DownloadedApk): ApkRiskReport {
        val name = apk.fileName.trim()
        if (!isApkFile(name)) {
            return ApkRiskReport(apk.fileName, isApk = false, ApkRiskLevel.LOW, 0, emptyList())
        }

        val findings = mutableListOf<ApkFinding>()

        if (hasDecoyExtension(name)) {
            findings += ApkFinding(
                ApkRiskCode.DISGUISED_EXTENSION, 50,
                "This installer is disguised as a document or media file ('$name'). Genuine files " +
                    "do not end in .apk. Do not install it.",
            )
        }

        val info = apk.apkInfo
        if (info != null && !info.isSigned) {
            findings += ApkFinding(
                ApkRiskCode.UNSIGNED_APK, 30,
                "This APK is not validly signed, so its maker cannot be verified.",
            )
        }

        if (info != null) {
            val dangerous = info.requestedPermissions.intersect(DANGEROUS_PERMISSIONS)
            if (dangerous.isNotEmpty()) {
                findings += ApkFinding(
                    ApkRiskCode.DANGEROUS_PERMISSIONS, 25,
                    "This APK asks for high-risk powers (${dangerous.joinToString(", ") { it.name }}) " +
                        "often abused by fraud apps.",
                )
            }
        }

        if (apk.sizeBytes in 1 until tinyApkBytes) {
            findings += ApkFinding(
                ApkRiskCode.TINY_DROPPER, 20,
                "This APK is unusually small (${apk.sizeBytes} bytes). Tiny installers often just " +
                    "download the real malware after you install them.",
            )
        }

        if (isMediaOrDocMime(apk.mimeType)) {
            findings += ApkFinding(
                ApkRiskCode.MIME_MISMATCH, 15,
                "The download was recorded as '${apk.mimeType}' but is actually an installable app.",
            )
        }

        val brandHit = brandKeywords.firstOrNull {
            name.lowercase().contains(it) || info?.appLabel?.lowercase()?.contains(it) == true
        }
        if (brandHit != null) {
            findings += ApkFinding(
                ApkRiskCode.BRAND_IMPERSONATION, 30,
                "This APK presents itself as a '$brandHit' app. Banking and payment apps should only " +
                    "be installed from the official app store, never from a downloaded file.",
            )
        }

        val host = apk.sourceHost?.lowercase()
        if (host != null && trustedSourceHosts.none { host == it || host.endsWith(".$it") }) {
            findings += ApkFinding(
                ApkRiskCode.UNKNOWN_SOURCE, 10,
                "This APK was downloaded from '$host', not a recognised app store.",
            )
        }

        val score = findings.sumOf { it.weight }.coerceIn(0, 100)
        return ApkRiskReport(apk.fileName, isApk = true, levelFor(score), score, findings.toList())
    }

    private fun levelFor(score: Int): ApkRiskLevel = when {
        score >= 60 -> ApkRiskLevel.CRITICAL
        score >= 35 -> ApkRiskLevel.HIGH
        score >= 15 -> ApkRiskLevel.MEDIUM
        else -> ApkRiskLevel.LOW
    }

    private fun isApkFile(name: String): Boolean = APK_EXT.containsMatchIn(name)

    private fun hasDecoyExtension(name: String): Boolean {
        val stripped = APK_EXT.replace(name, "")
        if (!stripped.contains('.')) return false
        return stripped.substringAfterLast('.').lowercase() in DECOY_EXTENSIONS
    }

    private fun isMediaOrDocMime(mime: String?): Boolean {
        if (mime == null) return false
        val m = mime.lowercase()
        return m.startsWith("image/") || m.startsWith("video/") || m.startsWith("audio/") ||
            m == "application/pdf" || m.startsWith("text/") ||
            m.startsWith("application/msword") || m.startsWith("application/vnd.")
    }

    companion object {
        private val APK_EXT = Regex("\\.(apk|apks|xapk|apkm)(\\.\\d+)?$", RegexOption.IGNORE_CASE)

        private val DECOY_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "jpg", "jpeg", "png", "gif", "mp4", "mkv", "mov", "mp3", "txt", "zip", "rar",
        )

        private val DANGEROUS_PERMISSIONS = setOf(
            SensitivePermission.READ_SMS,
            SensitivePermission.ACCESSIBILITY,
            SensitivePermission.DEVICE_ADMIN,
        )

        val DEFAULT_BRAND_KEYWORDS: Set<String> = setOf(
            "bank", "upi", "paytm", "phonepe", "gpay", "googlepay", "bhim",
            "sbi", "hdfc", "icici", "axis", "kotak", "kyc", "wallet",
        )

        val DEFAULT_TRUSTED_SOURCE_HOSTS: Set<String> = setOf(
            "play.google.com",
            "apkmirror.com", // widely-used, signature-verified mirror
        )
    }
}
