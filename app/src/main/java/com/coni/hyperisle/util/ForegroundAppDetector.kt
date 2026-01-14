package com.coni.hyperisle.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process



object ForegroundAppDetector {
    private const val LOOKBACK_MS = 5000L

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getForegroundPackage(context: Context, nowMs: Long = System.currentTimeMillis()): String? {
        if (!hasUsageAccess(context)) return null
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE)
            as? UsageStatsManager ?: return null
        val start = nowMs - LOOKBACK_MS
        val events = usageStatsManager.queryEvents(start, nowMs)
        val event = UsageEvents.Event()
        var lastForeground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForeground = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            if (isForeground) {
                lastForeground = event.packageName
            }
        }
        return lastForeground
    }

    fun isPackageForeground(context: Context, packageName: String): Boolean {
        val foregroundPackage = getForegroundPackage(context)
        return foregroundPackage != null && foregroundPackage == packageName
    }
}
