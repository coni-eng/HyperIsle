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
     */
    fun summary(): String {
        if (!BuildConfig.DEBUG) return "Priority diagnostics unavailable in release builds"
        
        val sb = StringBuilder()
        sb.appendLine("=== Priority Diagnostics Summary ===")
        sb.appendLine("Enabled: $enabled")
        sb.appendLine()
        sb.appendLine("Decision Counts:")
        sb.appendLine("  Allow: $allowCount")
        sb.appendLine("  Deny (Burst): $denyBurstCount")
        sb.appendLine("  Deny (Throttle): $denyThrottleCount")
        sb.appendLine()
        sb.appendLine("Recent Decisions (last ${ringBuffer.size}):")
        sb.appendLine("Format: timestamp|pkg|keyHash|decision|reasons")
        synchronized(ringBuffer) {
            ringBuffer.forEachIndexed { index, line ->
                sb.appendLine("  ${index + 1}. $line")
            }
        }
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
