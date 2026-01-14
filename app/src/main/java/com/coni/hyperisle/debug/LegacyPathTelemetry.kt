package com.coni.hyperisle.debug

import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.util.HiLog
import java.util.concurrent.ConcurrentLinkedDeque



/**
 * Telemetry for tracking legacy MINI/collapsed path hits during Anchor mode migration.
 * 
 * This is Phase 1 instrumentation - code is NOT deleted, only bypassed and logged.
 * All legacy paths remain in place for safety.
 * 
 * Log format:
 * - EVT=LEGACY_MINI_PATH_HIT feature=NOTIF|CALL|NAV branch=<name> reason=<originalReason> anchorEnabled=true stack=<caller>
 * - EVT=LEGACY_MINI_BYPASSED feature=NOTIF branch=<name> redirected=SHRINK_TO_ANCHOR
 */
object LegacyPathTelemetry {
    private const val TAG = "HyperIsleLegacy"
    
    enum class Feature {
        NOTIF,
        CALL,
        NAV,
        TIMER,
        MEDIA,
        OTHER
    }
    
    data class LegacyHit(
        val timestamp: Long,
        val feature: Feature,
        val branch: String,
        val reason: String,
        val anchorEnabled: Boolean,
        val bypassed: Boolean,
        val redirectedTo: String?,
        val caller: String
    ) {
        override fun toString(): String {
            val base = "EVT=LEGACY_MINI_PATH_HIT feature=$feature branch=$branch reason=$reason anchorEnabled=$anchorEnabled"
            return if (bypassed) {
                "$base | EVT=LEGACY_MINI_BYPASSED redirected=$redirectedTo"
            } else {
                "$base stack=$caller"
            }
        }
    }
    
    // Ring buffer for last N hits (debug builds only)
    private const val MAX_HITS = 50
    private val recentHits = ConcurrentLinkedDeque<LegacyHit>()
    
    // Counters by feature
    private val hitCounters = mutableMapOf<Feature, Int>()
    private val bypassCounters = mutableMapOf<Feature, Int>()
    
    /**
     * Log a legacy path hit.
     * Called when legacy MINI/collapsed code path is entered.
     * 
     * @param feature Which feature triggered this (NOTIF, CALL, NAV, etc.)
     * @param branch Name of the code branch (e.g., "scheduleAutoCollapse", "renderMiniPill")
     * @param reason Original reason for entering this path
     * @param anchorEnabled Whether anchor mode is currently enabled
     * @param bypassed Whether the path was bypassed (not executed)
     * @param redirectedTo If bypassed, where was it redirected (e.g., "SHRINK_TO_ANCHOR")
     */
    fun logHit(
        feature: Feature,
        branch: String,
        reason: String,
        anchorEnabled: Boolean,
        bypassed: Boolean = false,
        redirectedTo: String? = null
    ) {
        if (!BuildConfig.DEBUG) return
        
        val caller = getShortCaller()
        
        val hit = LegacyHit(
            timestamp = System.currentTimeMillis(),
            feature = feature,
            branch = branch,
            reason = reason,
            anchorEnabled = anchorEnabled,
            bypassed = bypassed,
            redirectedTo = redirectedTo,
            caller = caller
        )
        
        // Add to ring buffer
        recentHits.addFirst(hit)
        while (recentHits.size > MAX_HITS) {
            recentHits.removeLast()
        }
        
        // Update counters
        synchronized(hitCounters) {
            hitCounters[feature] = (hitCounters[feature] ?: 0) + 1
            if (bypassed) {
                bypassCounters[feature] = (bypassCounters[feature] ?: 0) + 1
            }
        }
        
        // Log to logcat
        if (bypassed) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=LEGACY_MINI_PATH_HIT feature=$feature branch=$branch reason=$reason anchorEnabled=$anchorEnabled")
            HiLog.d(HiLog.TAG_ISLAND, "EVT=LEGACY_MINI_BYPASSED feature=$feature branch=$branch redirected=$redirectedTo")
        } else {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=LEGACY_MINI_PATH_HIT feature=$feature branch=$branch reason=$reason anchorEnabled=$anchorEnabled stack=$caller")
        }
    }
    
    /**
     * Convenience: Log a hit and bypass for NOTIF feature.
     */
    fun logNotifBypass(branch: String, reason: String, anchorEnabled: Boolean) {
        logHit(
            feature = Feature.NOTIF,
            branch = branch,
            reason = reason,
            anchorEnabled = anchorEnabled,
            bypassed = true,
            redirectedTo = "SHRINK_TO_ANCHOR"
        )
    }
    
    /**
     * Convenience: Log a hit WITHOUT bypass (observation only).
     */
    fun logObservation(feature: Feature, branch: String, reason: String, anchorEnabled: Boolean) {
        logHit(
            feature = feature,
            branch = branch,
            reason = reason,
            anchorEnabled = anchorEnabled,
            bypassed = false,
            redirectedTo = null
        )
    }
    
    /**
     * Get recent hits for DiagnosticsScreen display.
     */
    fun getRecentHits(): List<LegacyHit> {
        return recentHits.toList()
    }
    
    /**
     * Get hit count for a specific feature.
     */
    fun getHitCount(feature: Feature): Int {
        return synchronized(hitCounters) { hitCounters[feature] ?: 0 }
    }
    
    /**
     * Get bypass count for a specific feature.
     */
    fun getBypassCount(feature: Feature): Int {
        return synchronized(bypassCounters) { bypassCounters[feature] ?: 0 }
    }
    
    /**
     * Get total hit count across all features.
     */
    fun getTotalHitCount(): Int {
        return synchronized(hitCounters) { hitCounters.values.sum() }
    }
    
    /**
     * Get total bypass count across all features.
     */
    fun getTotalBypassCount(): Int {
        return synchronized(bypassCounters) { bypassCounters.values.sum() }
    }
    
    /**
     * Clear all telemetry data.
     */
    fun clear() {
        recentHits.clear()
        synchronized(hitCounters) {
            hitCounters.clear()
            bypassCounters.clear()
        }
    }
    
    /**
     * Get summary string for diagnostics.
     */
    fun getSummary(): String {
        val total = getTotalHitCount()
        val bypassed = getTotalBypassCount()
        val notifHits = getHitCount(Feature.NOTIF)
        val notifBypassed = getBypassCount(Feature.NOTIF)
        val callHits = getHitCount(Feature.CALL)
        val navHits = getHitCount(Feature.NAV)
        
        return "Legacy Hits: $total (bypassed: $bypassed) | NOTIF: $notifHits/$notifBypassed | CALL: $callHits | NAV: $navHits"
    }
    
    private fun getShortCaller(): String {
        val stack = Thread.currentThread().stackTrace
        // Skip: getStackTrace, getShortCaller, logHit, actual caller
        val callerIndex = 5
        return if (stack.size > callerIndex) {
            val element = stack[callerIndex]
            "${element.fileName}:${element.lineNumber}"
        } else {
            "unknown"
        }
    }
}
