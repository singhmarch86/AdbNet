package dev.mobileshield.android.smishing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.mobileshield.core.smishing.SmishingLevel
import dev.mobileshield.core.smishing.SmishingReport

/** Posts a warning notification for a scam-looking SMS. */
object SmishingNotifier {

    fun warn(context: Context, sender: String?, report: SmishingReport) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Scam message alerts", NotificationManager.IMPORTANCE_HIGH)
        )

        val critical = report.level == SmishingLevel.CRITICAL
        val title = if (critical) "⚠ Likely scam SMS" else "⚠ Suspicious SMS"
        val from = sender?.let { "From $it\n" }.orEmpty()
        val detail = from + report.signals.joinToString("\n") { "• ${it.message}" }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(report.headline)
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .build()

        // Unique-ish id so multiple alerts don't overwrite each other.
        mgr.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    private const val CHANNEL_ID = "smishing_alerts"
}
