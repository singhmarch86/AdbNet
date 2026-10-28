package dev.mobileshield.android.guard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Best-effort "what app is in the foreground right now" using
 * [UsageStatsManager]. This needs the special "usage access" grant (not a
 * runtime permission) which the user toggles in Settings; [hasUsageAccess]
 * checks for it. Lighter and far less sensitive than an accessibility service.
 */
object ForegroundAppDetector {

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** The last package that moved to the foreground in the recent window. */
    fun currentForegroundPackage(context: Context, lookbackMillis: Long = 10_000): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - lookbackMillis, now)
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // ACTIVITY_RESUMED is the non-deprecated successor to
            // MOVE_TO_FOREGROUND and shares its value, so this works on API 26+.
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latest = event.packageName
            }
        }
        return latest
    }
}
