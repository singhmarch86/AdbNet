package dev.mobileshield.android.guard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.mobileshield.android.data.DeviceCheckup
import dev.mobileshield.core.guard.GuardAction
import dev.mobileshield.core.guard.PaymentContext
import dev.mobileshield.core.guard.ScamGuard
import dev.mobileshield.core.hygiene.DeviceHygieneAnalyzer
import dev.mobileshield.core.hygiene.HygieneReport

/**
 * The live "payment moment" monitor. While running it polls the foreground app
 * and, when a UPI/banking app is in front, feeds the current live context
 * (active call, screen-share heuristic, cached device-hygiene risk) into the
 * shared [ScamGuard]. On WARN/BLOCK it shows [GuardWarningOverlay].
 *
 * Passive signals only — it never reads payment amounts or on-screen content
 * here; the richer UPI-link context arrives through the in-app checker. The
 * optional accessibility service, when enabled, improves foreground and
 * screen-share detection via [LiveGuardState].
 */
class PaymentGuardService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val guard = ScamGuard()
    private val callMonitor by lazy { CallStateMonitor(this) }

    @Volatile
    private var hygiene: HygieneReport? = null
    private var lastRemoteAccessForegroundAt = 0L
    private var lastWarnedAt = 0L
    private var lastWarnedPackage: String? = null

    private val tick = object : Runnable {
        override fun run() {
            evaluate()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        callMonitor.start()
        // One-off hygiene snapshot in the background; refreshed occasionally.
        Thread {
            hygiene = DeviceHygieneAnalyzer().analyze(DeviceCheckup.collectApps(this))
        }.start()
        handler.post(tick)
    }

    private fun evaluate() {
        val foreground = LiveGuardState.foregroundPackage
            ?: ForegroundAppDetector.currentForegroundPackage(this)
        if (foreground != null) LiveGuardState.foregroundPackage = foreground

        val now = System.currentTimeMillis()
        if (foreground != null && foreground in DeviceHygieneAnalyzer.DEFAULT_REMOTE_ACCESS_PACKAGES) {
            lastRemoteAccessForegroundAt = now
        }

        if (!BankApps.isBankApp(foreground)) {
            GuardWarningOverlay.dismiss(this)
            return
        }

        val screenSharing = LiveGuardState.remoteAccessWindowActive ||
            (now - lastRemoteAccessForegroundAt) < RECENT_REMOTE_ACCESS_MS

        val decision = guard.evaluate(
            PaymentContext(
                hygiene = hygiene,
                callActive = callMonitor.isCallActive,
                screenSharingActive = screenSharing,
            )
        )

        if (decision.action != GuardAction.ALLOW) {
            val recentlyWarned = foreground == lastWarnedPackage &&
                (now - lastWarnedAt) < WARN_DEBOUNCE_MS
            if (!recentlyWarned) {
                lastWarnedAt = now
                lastWarnedPackage = foreground
                GuardWarningOverlay.show(this, decision)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        callMonitor.stop()
        GuardWarningOverlay.dismiss(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Payment protection", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Payment protection is on")
            .setContentText("Watching for scam patterns when you open a payment app.")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 1_500L
        private const val RECENT_REMOTE_ACCESS_MS = 30_000L
        private const val WARN_DEBOUNCE_MS = 20_000L
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "payment_guard"

        fun start(context: Context) {
            val intent = Intent(context, PaymentGuardService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PaymentGuardService::class.java))
        }
    }
}
