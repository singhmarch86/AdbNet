package dev.mobileshield.android.policy

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * Device admin component for mobile-shield. For real OS-level enforcement the
 * app must be provisioned as **device owner** (via `adb dpm set-device-owner`
 * on a fresh device, or QR/EMM provisioning) — this receiver is the admin that
 * [PolicyEnforcer] acts through.
 */
class ShieldDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context, ShieldDeviceAdminReceiver::class.java)
    }
}
