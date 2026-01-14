package com.coni.hyperisle.util

import android.content.Context
import com.coni.hyperisle.BuildConfig
import org.json.JSONArray
import org.json.JSONObject



/**
 * In-memory diagnostics for PriorityEngine decisions.
 * 
 * This is a DEBUG-ONLY utility for tracking priority decisions (allow/deny with reason codes).
 * When disabled (or in release builds), ALL methods are NO-OP with zero overhead.
 * 
 * PII-safe: Only logs packageName and keyHash, never notification title/text.
 * No disk I/O, no network calls.
 */
object PriorityDiagnostics {

    @Volatile
    private var enabled: Boolean = false

    // Counters for decision types
    @Volatile
    var allowCount: Int = 0
        private set
    @Volatile
    var denyBurstCount: Int = 0
        private set
    @Volatile
    var denyThrottleCount: Int = 0
        private set
    
    // v0.9.5: Profile-affected decision counters (debug visibility)
    @Volatile
    var profileStrictAppliedCount: Int = 0
        private set
    @Volatile
    var profileLenientAppliedCount: Int = 0
        private set

    // Ring buffer for last 50 diagnostic lines
    private val ringBuffer = ArrayDeque<String>(50)
    private const val MAX_BUFFER_SIZE = 50

    /**
     * Sets the enabled state. Only effective in debug builds.
     */
    fun setEnabled(value: Boolean) {
        if (!BuildConfig.DEBUG) return
        enabled = value
    }

    /**
     * Returns true if diagnostics are enabled AND this is a debug build.
     */
    fun isEnabled(): Boolean = BuildConfig.DEBUG && enabled

    /**
     * Records a priority decision. NO-OP if disabled or release build.
     * 
     * @param pkg Package name (safe to log)
     * @param keyHash Hash of sbn.key (safe to log, no PII)
     * @param decision "ALLOW" or "DENY"
     * @param reason Comma-separated reason codes (e.g., "BURST,PKG:com.example")
     */
    fun record(pkg: String, keyHash: Int, decision: String, reason: String) {
        if (!isEnabled()) return
        
        val timestamp = System.currentTimeMillis()
        val line = "$timestamp|$pkg|$keyHash|$decision|$reason"
        
        synchronized(ringBuffer) {
            if (ringBuffer.size >= MAX_BUFFER_SIZE) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(line)
        }
        
        // Update counters
        when {
            decision == "ALLOW" -> allowCount++
            reason.contains("BURST") -> denyBurstCount++
            reason.contains("THROTTLE") -> denyThrottleCount++
        }
        
        // v0.9.5: Track profile-affected decisions for diagnostics clarity
        if (reason.contains("PROFILE_STRICT_APPLIED")) profileStrictAppliedCount++
        if (reason.contains("PROFILE_LENIENT_APPLIED")) profileLenientAppliedCount++
    }

    /**
     * Returns a summary string with counters and recent diagnostic lines.
     * Format is designed to be copy-pasteable for debugging.
     * @param timeRangeMs Time range in milliseconds (0 = all entries)
     */
    fun summary(timeRangeMs: Long = 0): String {
        if (!BuildConfig.DEBUG) return "Priority diagnostics unavailable in release builds"
        
        val now = System.currentTimeMillis()
        val cutoffTime = if (timeRangeMs > 0) now - timeRangeMs else 0L
        
        val sb = StringBuilder()
        sb.appendLine("=== Priority Diagnostics Summary ===")
        sb.appendLine("Enabled: $enabled")
        sb.appendLine()
        sb.appendLine("Decision Counts:")
        sb.appendLine("  Allow: $allowCount")
        sb.appendLine("  Deny (Burst): $denyBurstCount")
        sb.appendLine("  Deny (Throttle): $denyThrottleCount")
        sb.appendLine()
        sb.appendLine("Profile Impact (decisions materially affected):")
        sb.appendLine("  STRICT profile applied: $profileStrictAppliedCount")
        sb.appendLine("  LENIENT profile applied: $profileLenientAppliedCount")
        sb.appendLine()
        
        val filteredEntries = synchronized(ringBuffer) {
            ringBuffer.filter { line ->
                val timestamp = line.substringBefore('|').toLongOrNull() ?: 0L
                timestamp >= cutoffTime
            }
        }
        
        sb.appendLine("Recent Decisions (last ${filteredEntries.size}):")
        sb.appendLine("Format: timestamp|pkg|keyHash|decision|reasons")
        filteredEntries.forEachIndexed { index, line ->
            sb.appendLine("  ${index + 1}. $line")
        }
        return sb.toString()
    }

    /**
     * Generates export content with app metadata and time range info.
     * @param context Application context (for session header)
     * @param appName Application name
     * @param versionName Version name
     * @param versionCode Build number
     * @param timeRangeMs Time range in milliseconds
     * @param timeRangeLabel Human-readable time range label
     * @param format Export format ("plain" or "json")
     */
    fun exportContent(
        context: Context,
        appName: String,
        versionName: String,
        versionCode: Int,
        timeRangeMs: Long,
        timeRangeLabel: String,
        format: String = "plain"
    ): String {
        if (!BuildConfig.DEBUG) return "Export unavailable in release builds"
        
        return if (format == "json") {
            exportContentJson(context, appName, versionName, versionCode, timeRangeMs, timeRangeLabel)
        } else {
            exportContentPlain(context, appName, versionName, versionCode, timeRangeMs, timeRangeLabel)
        }
    }

    private fun exportContentPlain(
        context: Context,
        appName: String,
        versionName: String,
        versionCode: Int,
        timeRangeMs: Long,
        timeRangeLabel: String
    ): String {
        val sb = StringBuilder()
        
        // DEBUG-ONLY: Prepend session header
        if (BuildConfig.DEBUG) {
            val sessionHeader = SessionHeaderHelper.generatePlainTextHeader(
                context = context,
                appName = appName,
                versionName = versionName,
                versionCode = versionCode,
                timeRangeLabel = timeRangeLabel,
                timelineEnabled = DebugTimeline.isEnabled()
            )
            sb.append(sessionHeader)
        }
        
        sb.appendLine("$appName Priority Diagnostics Export")
        sb.appendLine("Version: $versionName (Build $versionCode)")
        sb.appendLine("Time Range: $timeRangeLabel")
        sb.appendLine("Export Format: Plain Text")
        sb.appendLine("Exported: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        sb.appendLine()
        sb.appendLine(summary(timeRangeMs))
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine("No notification content included.")
        return sb.toString()
    }

    private fun exportContentJson(
        context: Context,
        appName: String,
        versionName: String,
        versionCode: Int,
        timeRangeMs: Long,
        timeRangeLabel: String
    ): String {
        val now = System.currentTimeMillis()
        val cutoffTime = if (timeRangeMs > 0) now - timeRangeMs else 0L
        
        val filteredEntries = synchronized(ringBuffer) {
            ringBuffer.filter { line ->
                val timestamp = line.substringBefore('|').toLongOrNull() ?: 0L
                timestamp >= cutoffTime
            }
        }
        
        val json = JSONObject()
        
        // DEBUG-ONLY: Add session header as top-level object
        if (BuildConfig.DEBUG) {
            val sessionHeader = SessionHeaderHelper.generateJsonHeader(
                context = context,
                appName = appName,
                versionName = versionName,
                versionCode = versionCode,
                timeRangeLabel = timeRangeLabel,
                timelineEnabled = DebugTimeline.isEnabled()
            )
            sessionHeader?.let { json.put("session", it) }
        }
        
        json.put("export_type", "priority_diagnostics")
        json.put("app_name", appName)
        json.put("version_name", versionName)
        json.put("version_code", versionCode)
        json.put("time_range", timeRangeLabel)
        json.put("export_format", "JSON")
        json.put("exported_at", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
        json.put("enabled", enabled)
        
        val counters = JSONObject()
        counters.put("allow", allowCount)
        counters.put("deny_burst", denyBurstCount)
        counters.put("deny_throttle", denyThrottleCount)
        json.put("counters", counters)
        
        // v0.9.5: Include profile impact in JSON export (debug builds only)
        val profileImpact = JSONObject()
        profileImpact.put("strict_applied_count", profileStrictAppliedCount)
        profileImpact.put("lenient_applied_count", profileLenientAppliedCount)
        profileImpact.put("description", "Count of decisions where per-app profile materially affected suppression")
        json.put("profile_impact", profileImpact)
        
        val decisions = JSONArray()
        filteredEntries.forEach { line ->
            val parts = line.split('|')
            if (parts.size >= 5) {
                val decisionObj = JSONObject()
                decisionObj.put("timestamp", parts[0].toLongOrNull() ?: 0L)
                decisionObj.put("package", parts[1])
                decisionObj.put("key_hash", parts[2].toIntOrNull() ?: 0)
                decisionObj.put("decision", parts[3])
                decisionObj.put("reason", parts[4])
                decisions.put(decisionObj)
            }
        }
        json.put("decisions", decisions)
        json.put("decision_count", filteredEntries.size)
        
        json.put("privacy_note", "No notification content included.")
        
        return json.toString(2)
    }

    /**
     * Clears all counters and the ring buffer.
     */
    fun clear() {
        if (!BuildConfig.DEBUG) return
        allowCount = 0
        denyBurstCount = 0
        denyThrottleCount = 0
        profileStrictAppliedCount = 0
        profileLenientAppliedCount = 0
        synchronized(ringBuffer) {
            ringBuffer.clear()
        }
    }
}
