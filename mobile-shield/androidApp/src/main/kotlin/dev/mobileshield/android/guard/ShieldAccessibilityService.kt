package dev.mobileshield.android.guard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.mobileshield.core.hygiene.DeviceHygieneAnalyzer

/**
 * OPTIONAL, opt-in enhancement. When the user enables it, this service gives
 * the guard event-driven (not polled) foreground detection and a reliable
 * read on screen-sharing / overlay windows — closing the gaps the UsageStats
 * poller cannot. It writes only into [LiveGuardState]; [PaymentGuardService]
 * still owns the decision and the warning.
 *
 * It intentionally does NOT harvest on-screen text. Reading accessibility node
 * content is powerful but is exactly what scammers abuse and what Play restricts;
 * limiting this to window-level signals keeps the capability proportionate.
 */
class ShieldAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        LiveGuardState.accessibilityConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (pkg != null) {
                    LiveGuardState.foregroundPackage = pkg
                    LiveGuardState.remoteAccessWindowActive =
                        pkg in DeviceHygieneAnalyzer.DEFAULT_REMOTE_ACCESS_PACKAGES
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                // A remote-access tool often keeps a floating control window up
                // even when it is not the foreground app.
                LiveGuardState.overlayDetected = windows?.any { it.isActive && it.type ==
                    android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION } == true
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        LiveGuardState.accessibilityConnected = false
        LiveGuardState.remoteAccessWindowActive = false
        super.onDestroy()
    }
}
