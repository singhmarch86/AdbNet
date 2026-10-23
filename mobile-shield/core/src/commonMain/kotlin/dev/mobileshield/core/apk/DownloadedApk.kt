package dev.mobileshield.core.apk

import dev.mobileshield.core.hygiene.SensitivePermission

/**
 * A downloaded APK file, as gathered by the platform shell from the Downloads
 * collection. Everything here is metadata the platform can read cheaply; the
 * optional [apkInfo] is filled in only when it could parse the package.
 */
data class DownloadedApk(
    val fileName: String,
    val sizeBytes: Long,
    /** Host the file was downloaded from, if known (e.g. MediaStore columns). */
    val sourceHost: String? = null,
    /** MIME type recorded for the download, if known. */
    val mimeType: String? = null,
    /** Epoch millis the file was added, if known. */
    val downloadedAtMillis: Long? = null,
    /** Parsed manifest info, when the platform could parse the APK. */
    val apkInfo: ApkInfo? = null,
)

/** Manifest-level facts about a parsed APK. */
data class ApkInfo(
    val packageId: String,
    val appLabel: String? = null,
    /** Sensitive capabilities requested in the manifest. */
    val requestedPermissions: Set<SensitivePermission> = emptySet(),
    /** SHA-256 of the signing certificate, lowercase hex, if available. */
    val signerSha256: String? = null,
    /** False when the APK carries no valid signature. */
    val isSigned: Boolean = true,
)
