package dev.mobileshield.android.data

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dev.mobileshield.core.apk.ApkInfo
import dev.mobileshield.core.apk.DownloadedApk
import dev.mobileshield.core.hygiene.InstallSource
import dev.mobileshield.core.hygiene.InstalledApp
import dev.mobileshield.core.hygiene.SensitivePermission
import java.io.File
import java.security.MessageDigest

/**
 * Gathers the raw device facts that the (pure, shared) `core` analyzers judge:
 * installed apps for [dev.mobileshield.core.hygiene.DeviceHygieneAnalyzer] and
 * downloaded APKs for [dev.mobileshield.core.apk.ApkScanner]. All inspection and
 * scoring logic lives in `core`; this file only reads the platform.
 */
object DeviceCheckup {

    // ---- Installed apps -------------------------------------------------

    @Suppress("DEPRECATION")
    fun collectApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val a11y = enabledAccessibilityPackages(context)
        val notifListeners = enabledNotificationListenerPackages(context)
        val admins = deviceAdminPackages(context)

        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        return packages.mapNotNull { pi ->
            val ai = pi.applicationInfo ?: return@mapNotNull null
            val pkg = pi.packageName
            val granted = grantedPermissions(pi)
            val requested = pi.requestedPermissions?.toSet() ?: emptySet()

            val perms = buildSet {
                if ("android.permission.READ_SMS" in granted) add(SensitivePermission.READ_SMS)
                if ("android.permission.SYSTEM_ALERT_WINDOW" in requested) add(SensitivePermission.DISPLAY_OVER_OTHER_APPS)
                if ("android.permission.REQUEST_INSTALL_PACKAGES" in requested) add(SensitivePermission.INSTALL_UNKNOWN_APPS)
                if (pkg in admins) add(SensitivePermission.DEVICE_ADMIN)
                if (pkg in notifListeners) add(SensitivePermission.NOTIFICATION_LISTENER)
            }

            InstalledApp(
                packageId = pkg,
                label = pm.getApplicationLabel(ai).toString(),
                installSource = installSource(pm, pkg),
                permissions = perms,
                accessibilityServiceEnabled = pkg in a11y,
                isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun installSource(pm: PackageManager, pkg: String): InstallSource {
        val installer = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                pm.getInstallerPackageName(pkg)
            }
        } catch (_: Exception) {
            null
        }
        return when (installer) {
            "com.android.vending" -> InstallSource.PLAY_STORE
            null -> InstallSource.SIDELOADED
            "com.amazon.venezia",
            "com.sec.android.app.samsungapps",
            "com.huawei.appmarket",
            "com.xiaomi.market",
            "com.oppo.market" -> InstallSource.OTHER_APP_STORE
            else -> InstallSource.OTHER_APP_STORE
        }
    }

    private fun grantedPermissions(pi: PackageInfo): Set<String> {
        val reqs = pi.requestedPermissions ?: return emptySet()
        val flags = pi.requestedPermissionsFlags ?: return emptySet()
        val out = HashSet<String>()
        for (i in reqs.indices) {
            if (i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0) {
                out.add(reqs[i])
            }
        }
        return out
    }

    private fun enabledAccessibilityPackages(context: Context): Set<String> {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return emptySet()
        return am.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).mapNotNull { it.resolveInfo?.serviceInfo?.packageName }.toSet()
    }

    private fun enabledNotificationListenerPackages(context: Context): Set<String> {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ).orEmpty()
        return flat.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
            .toSet()
    }

    private fun deviceAdminPackages(context: Context): Set<String> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return emptySet()
        return dpm.activeAdmins?.map { it.packageName }?.toSet() ?: emptySet()
    }

    // ---- Downloaded APKs ------------------------------------------------

    fun collectDownloadedApks(context: Context): List<DownloadedApk> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%.apk%")

        val results = ArrayList<DownloadedApk>()
        context.contentResolver.query(collection, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                results.add(
                    DownloadedApk(
                        fileName = name,
                        sizeBytes = c.getLong(sizeCol),
                        sourceHost = null, // MediaStore rarely exposes the origin URL reliably
                        mimeType = if (c.isNull(mimeCol)) null else c.getString(mimeCol),
                        downloadedAtMillis = c.getLong(dateCol) * 1000,
                        apkInfo = parseApk(context, uri),
                    )
                )
            }
        }
        return results
    }

    /** Best-effort manifest/signature parse by copying the APK to cache. */
    private fun parseApk(context: Context, uri: android.net.Uri): ApkInfo? {
        val temp = File.createTempFile("scan", ".apk", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: return null

            val pm = context.packageManager
            val flags = PackageManager.GET_PERMISSIONS or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0)
            @Suppress("DEPRECATION")
            val pi = pm.getPackageArchiveInfo(temp.absolutePath, flags) ?: return null

            val requested = pi.requestedPermissions?.toSet() ?: emptySet()
            val perms = buildSet {
                if ("android.permission.READ_SMS" in requested) add(SensitivePermission.READ_SMS)
                if ("android.permission.BIND_ACCESSIBILITY_SERVICE" in requested) add(SensitivePermission.ACCESSIBILITY)
                if ("android.permission.BIND_DEVICE_ADMIN" in requested) add(SensitivePermission.DEVICE_ADMIN)
                if ("android.permission.SYSTEM_ALERT_WINDOW" in requested) add(SensitivePermission.DISPLAY_OVER_OTHER_APPS)
            }

            val label = pi.applicationInfo?.let { ai ->
                ai.sourceDir = temp.absolutePath
                ai.publicSourceDir = temp.absolutePath
                pm.getApplicationLabel(ai).toString()
            }

            ApkInfo(
                packageId = pi.packageName ?: "",
                appLabel = label,
                requestedPermissions = perms,
                signerSha256 = signerSha256(pi),
                isSigned = hasSignature(pi),
            )
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    private fun hasSignature(pi: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return pi.signingInfo != null
        }
        @Suppress("DEPRECATION")
        return pi.signatures?.isNotEmpty() == true
    }

    private fun signerSha256(pi: PackageInfo): String? {
        val signatureBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pi.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            pi.signatures?.firstOrNull()?.toByteArray()
        } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(signatureBytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
