package com.coni.hyperisle.util

import com.coni.hyperisle.BuildConfig

/**
 * In-memory diagnostics for notification action handling.
 * 
 * This is a DEBUG-ONLY utility for tracking PendingIntent inference and action handling.
 * When disabled (or in release builds), ALL methods are NO-OP with zero overhead.
 * 
 * No disk I/O, no network calls, no PII logging.
 */
object ActionDiagnostics {

    @Volatile
    private var enabled: Boolean = false

    // Counters for inferred intent types
    @Volatile
    var inferredActivityCount: Int = 0
        private set
    @Volatile
    var inferredBroadcastCount: Int = 0
        private set
    @Volatile
    var inferredServiceCount: Int = 0
        private set
    @Volatile
    var inferredUnknownCount: Int = 0
        private set
    @Volatile
    var fallbackUsedCount: Int = 0
        private set

    // Ring buffer for last 50 diagnostic lines with timestamps
    private data class DiagnosticEntry(val timestamp: Long, val line: String)
    private val ringBuffer = ArrayDeque<DiagnosticEntry>(50)
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
     * Increments the activity counter. NO-OP if disabled.
     */
    fun incrementActivity() {
        if (!isEnabled()) return
        inferredActivityCount++
    }

    /**
     * Increments the broadcast counter. NO-OP if disabled.
     */
    fun incrementBroadcast() {
        if (!isEnabled()) return
        inferredBroadcastCount++
    }

    /**
     * Increments the service counter. NO-OP if disabled.
     */
    fun incrementService() {
        if (!isEnabled()) return
        inferredServiceCount++
    }

    /**
     * Increments the unknown counter. NO-OP if disabled.
     */
    fun incrementUnknown() {
        if (!isEnabled()) return
        inferredUnknownCount++
    }

    /**
     * Increments the fallback used counter. NO-OP if disabled.
     */
    fun incrementFallback() {
        if (!isEnabled()) return
        fallbackUsedCount++
    }

    /**
     * Records a diagnostic line to the ring buffer. NO-OP if disabled.
     * The line should NOT contain PII (no notification title/text).
     */
    fun record(line: String) {
        if (!isEnabled()) return
        synchronized(ringBuffer) {
            if (ringBuffer.size >= MAX_BUFFER_SIZE) {
                ringBuffer.removeFirst()
            }
            ringBuffer.addLast(DiagnosticEntry(System.currentTimeMillis(), line))
        }
    }

    /**
     * Returns a summary string with counters and recent diagnostic lines.
     * @param timeRangeMs Time range in milliseconds (0 = all entries)
     */
    fun summary(timeRangeMs: Long = 0): String {
        if (!BuildConfig.DEBUG) return "Diagnostics unavailable in release builds"
        
        val now = System.currentTimeMillis()
        val cutoffTime = if (timeRangeMs > 0) now - timeRangeMs else 0L
        
        val sb = StringBuilder()
        sb.appendLine("=== Action Diagnostics Summary ===")
        sb.appendLine("Enabled: $enabled")
        sb.appendLine()
        sb.appendLine("Inferred Intent Types:")
        sb.appendLine("  Activity: $inferredActivityCount")
        sb.appendLine("  Broadcast: $inferredBroadcastCount")
        sb.appendLine("  Service: $inferredServiceCount")
        sb.appendLine("  Unknown: $inferredUnknownCount")
        sb.appendLine("  Fallback Used: $fallbackUsedCount")
        sb.appendLine()
        
        val filteredEntries = synchronized(ringBuffer) {
            ringBuffer.filter { it.timestamp >= cutoffTime }
        }
        
        sb.appendLine("Recent Diagnostic Lines (last ${filteredEntries.size}):")
        filteredEntries.forEachIndexed { index, entry ->
            sb.appendLine("  ${index + 1}. ${entry.line}")
        }
        return sb.toString()
    }

    /**
     * Generates export content with app metadata and time range info.
     * @param appName Application name
     * @param versionName Version name
     * @param versionCode Build number
     * @param timeRangeMs Time range in milliseconds
     * @param timeRangeLabel Human-readable time range label
     */
    fun exportContent(
        appName: String,
        versionName: String,
        versionCode: Int,
        timeRangeMs: Long,
        timeRangeLabel: String
    ): String {
        if (!BuildConfig.DEBUG) return "Export unavailable in release builds"
        
        val sb = StringBuilder()
        sb.appendLine("$appName Diagnostics Export")
        sb.appendLine("Version: $versionName (Build $versionCode)")
        sb.appendLine("Time Range: $timeRangeLabel")
        sb.appendLine("Exported: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        sb.appendLine()
        sb.appendLine(summary(timeRangeMs))
        sb.appendLine()
        sb.appendLine("---")
        sb.appendLine("No notification content included.")
        return sb.toString()
    }

    /**
     * Clears all counters and the ring buffer.
     */
    fun clear() {
        if (!BuildConfig.DEBUG) return
        inferredActivityCount = 0
        inferredBroadcastCount = 0
        inferredServiceCount = 0
        inferredUnknownCount = 0
        fallbackUsedCount = 0
        synchronized(ringBuffer) {
            ringBuffer.clear()
        }
    }
}
