package com.coni.hyperisle.util

import com.coni.hyperisle.BuildConfig

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
        sb.appendLine("$appName Priority Diagnostics Export")
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
        allowCount = 0
        denyBurstCount = 0
        denyThrottleCount = 0
        synchronized(ringBuffer) {
            ringBuffer.clear()
        }
    }
}
