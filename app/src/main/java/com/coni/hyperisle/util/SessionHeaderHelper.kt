package com.coni.hyperisle.util

import android.content.Context
import android.os.Build
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.data.AppPreferences
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * DEBUG-ONLY utility for generating session headers in diagnostics exports.
 * 
 * Provides PII-safe device and app context information to improve troubleshooting.
 * All methods are NO-OP in release builds.
 */
object SessionHeaderHelper {

    /**
     * Generates a plain text session header for diagnostics export.
     * Returns empty string in release builds.
     */
    fun generatePlainTextHeader(
        context: Context,
        appName: String,
        versionName: String,
        versionCode: Int,
        timeRangeLabel: String,
        timelineEnabled: Boolean
    ): String {
        if (!BuildConfig.DEBUG) return ""
        
        val sb = StringBuilder()
        sb.appendLine("=== Session ===")
        sb.appendLine("App name: $appName")
        sb.appendLine("App version: $versionName ($versionCode)")
        sb.appendLine("Build type: debug")
        sb.appendLine("Android API: ${Build.VERSION.SDK_INT}")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        
        // ROM display or build incremental (optional, may be unavailable)
        if (!Build.DISPLAY.isNullOrBlank()) {
            sb.appendLine("ROM display: ${Build.DISPLAY}")
        }
        if (!Build.VERSION.INCREMENTAL.isNullOrBlank()) {
            sb.appendLine("Build incremental: ${Build.VERSION.INCREMENTAL}")
        }
        
        sb.appendLine("Locale: ${Locale.getDefault()}")
        
        val tz = TimeZone.getDefault()
        val tzOffset = tz.getOffset(System.currentTimeMillis()) / (1000 * 60 * 60)
        val tzSign = if (tzOffset >= 0) "+" else ""
        sb.appendLine("Timezone: ${tz.id} (GMT$tzSign$tzOffset)")
        
        val exportTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        sb.appendLine("Exported at: $exportTime")
        sb.appendLine("Selected range: $timeRangeLabel")
        sb.appendLine("Timeline enabled: $timelineEnabled")
        
        val shadeCancelCount = getShadeCancelEnabledCount(context)
        sb.appendLine("Shade-cancel enabled apps: $shadeCancelCount")
        
        sb.appendLine()
        return sb.toString()
    }

    /**
     * Generates a JSON session object for diagnostics export.
     * Returns null in release builds.
     */
    fun generateJsonHeader(
        context: Context,
        appName: String,
        versionName: String,
        versionCode: Int,
        timeRangeLabel: String,
        timelineEnabled: Boolean
    ): JSONObject? {
        if (!BuildConfig.DEBUG) return null
        
        val session = JSONObject()
        session.put("appName", appName)
        session.put("versionName", versionName)
        session.put("versionCode", versionCode)
        session.put("buildType", "debug")
        session.put("androidApi", Build.VERSION.SDK_INT)
        session.put("manufacturer", Build.MANUFACTURER)
        session.put("model", Build.MODEL)
        
        // Optional fields - only include if available
        if (!Build.DISPLAY.isNullOrBlank()) {
            session.put("romDisplay", Build.DISPLAY)
        }
        if (!Build.VERSION.INCREMENTAL.isNullOrBlank()) {
            session.put("buildIncremental", Build.VERSION.INCREMENTAL)
        }
        
        session.put("locale", Locale.getDefault().toString())
        
        val tz = TimeZone.getDefault()
        val tzOffset = tz.getOffset(System.currentTimeMillis()) / (1000 * 60 * 60)
        val tzSign = if (tzOffset >= 0) "+" else ""
        session.put("timezone", "${tz.id} (GMT$tzSign$tzOffset)")
        
        val exportTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        session.put("exportedAt", exportTime)
        session.put("range", timeRangeLabel)
        session.put("timelineEnabled", timelineEnabled)
        
        val shadeCancelCount = getShadeCancelEnabledCount(context)
        session.put("shadeCancelEnabledCount", shadeCancelCount)
        
        return session
    }

    /**
     * Counts the number of apps with shade-cancel enabled.
     * Returns 0 in release builds or if database is unavailable.
     */
    private fun getShadeCancelEnabledCount(context: Context): Int {
        if (!BuildConfig.DEBUG) return 0
        
        return try {
            runBlocking {
                val preferences = AppPreferences(context)
                val dao = com.coni.hyperisle.data.db.AppDatabase.getDatabase(context).settingsDao()
                val shadeCancelSettings = dao.getByPrefix("shade_cancel_")
                shadeCancelSettings.count { it.value == "true" }
            }
        } catch (e: Exception) {
            0
        }
    }
}
