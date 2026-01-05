package com.d4viddf.hyperbridge.util

import android.content.Context
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.models.NotificationType
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Smart Priority Engine for reducing notification spam.
 * 
 * Rules:
 * - ≥3 notifications from same pkg:type in 10s → only latest shown (burst collapse)
 * - High dismiss rate → auto throttle (temporary)
 * - CALL/TIMER/NAV always allowed (priority types)
 * 
 * Persistence via AppPreferences (Room key/value):
 * - priority_dismiss_{pkg}_{type}_{yyyyMMdd} - daily dismiss counter
 * - priority_throttle_until_{pkg}_{type} - throttle expiry timestamp
 */
object PriorityEngine {

    private const val TAG = "PriorityEngine"

    // In-memory burst tracking: groupKey -> list of timestamps
    private val burstTracker = ConcurrentHashMap<String, MutableList<Long>>()

    // Priority types that are always allowed
    private val PRIORITY_TYPES = setOf(
        NotificationType.CALL.name,
        NotificationType.TIMER.name,
        NotificationType.NAVIGATION.name
    )

    // Aggressiveness levels
    const val AGGRESSIVENESS_LOW = 0
    const val AGGRESSIVENESS_MEDIUM = 1
    const val AGGRESSIVENESS_HIGH = 2

    // Thresholds based on aggressiveness
    private fun getBurstThreshold(aggressiveness: Int): Int = when (aggressiveness) {
        AGGRESSIVENESS_LOW -> 5
        AGGRESSIVENESS_MEDIUM -> 3
        AGGRESSIVENESS_HIGH -> 2
        else -> 3
    }

    private fun getBurstWindowMs(aggressiveness: Int): Long = when (aggressiveness) {
        AGGRESSIVENESS_LOW -> 15000L
        AGGRESSIVENESS_MEDIUM -> 10000L
        AGGRESSIVENESS_HIGH -> 5000L
        else -> 10000L
    }

    private fun getDismissThreshold(aggressiveness: Int): Int = when (aggressiveness) {
        AGGRESSIVENESS_LOW -> 15
        AGGRESSIVENESS_MEDIUM -> 10
        AGGRESSIVENESS_HIGH -> 5
        else -> 10
    }

    private fun getThrottleDurationMs(aggressiveness: Int): Long = when (aggressiveness) {
        AGGRESSIVENESS_LOW -> 30 * 60 * 1000L      // 30 min
        AGGRESSIVENESS_MEDIUM -> 60 * 60 * 1000L  // 1 hour
        AGGRESSIVENESS_HIGH -> 2 * 60 * 60 * 1000L // 2 hours
        else -> 60 * 60 * 1000L
    }

    /**
     * Decision result from the priority engine.
     */
    sealed class Decision {
        object Allow : Decision()
        object BlockBurst : Decision()
        object BlockThrottle : Decision()
    }

    /**
     * Evaluates whether a notification should be shown.
     * 
     * @param context Application context
     * @param preferences AppPreferences instance
     * @param packageName Source app package
     * @param typeName NotificationType name
     * @param smartPriorityEnabled Whether smart priority is enabled
     * @param aggressiveness Aggressiveness level (0-2)
     * @return Decision indicating whether to allow or block
     */
    suspend fun evaluate(
        context: Context,
        preferences: AppPreferences,
        packageName: String,
        typeName: String,
        smartPriorityEnabled: Boolean,
        aggressiveness: Int
    ): Decision {
        // If disabled, always allow
        if (!smartPriorityEnabled) return Decision.Allow

        // Priority types always allowed
        if (PRIORITY_TYPES.contains(typeName)) return Decision.Allow

        val groupKey = "${packageName}:${typeName}"

        // Check throttle first
        if (isThrottled(preferences, packageName, typeName)) {
            return Decision.BlockThrottle
        }

        // Check burst
        if (isBurst(groupKey, aggressiveness)) {
            return Decision.BlockBurst
        }

        return Decision.Allow
    }

    /**
     * Records a notification being shown (for burst tracking).
     */
    fun recordShown(packageName: String, typeName: String) {
        val groupKey = "${packageName}:${typeName}"
        val now = System.currentTimeMillis()
        
        burstTracker.compute(groupKey) { _, list ->
            val timestamps = list ?: mutableListOf()
            timestamps.add(now)
            // Keep only last 10 entries to prevent memory bloat
            if (timestamps.size > 10) {
                timestamps.removeAt(0)
            }
            timestamps
        }
    }

    /**
     * Checks if we're in a burst scenario for this groupKey.
     */
    private fun isBurst(groupKey: String, aggressiveness: Int): Boolean {
        val timestamps = burstTracker[groupKey] ?: return false
        val now = System.currentTimeMillis()
        val windowMs = getBurstWindowMs(aggressiveness)
        val threshold = getBurstThreshold(aggressiveness)

        val recentCount = timestamps.count { now - it < windowMs }
        return recentCount >= threshold
    }

    /**
     * Checks if this pkg:type is currently throttled.
     */
    private suspend fun isThrottled(
        preferences: AppPreferences,
        packageName: String,
        typeName: String
    ): Boolean {
        val throttleUntil = preferences.getPriorityThrottleUntil(packageName, typeName)
        return throttleUntil > System.currentTimeMillis()
    }

    /**
     * Increments the dismiss counter for today.
     * Called from IslandActionReceiver on DISMISS action.
     */
    suspend fun recordDismiss(
        preferences: AppPreferences,
        packageName: String,
        typeName: String,
        aggressiveness: Int
    ) {
        // Priority types don't get throttled
        if (PRIORITY_TYPES.contains(typeName)) return

        val today = getTodayKey()
        val currentCount = preferences.getPriorityDismissCount(packageName, typeName, today)
        val newCount = currentCount + 1
        preferences.setPriorityDismissCount(packageName, typeName, today, newCount)

        // Check if we should auto-throttle
        val threshold = getDismissThreshold(aggressiveness)
        if (newCount >= threshold) {
            val throttleDuration = getThrottleDurationMs(aggressiveness)
            val throttleUntil = System.currentTimeMillis() + throttleDuration
            preferences.setPriorityThrottleUntil(packageName, typeName, throttleUntil)
        }
    }

    /**
     * Manually throttle an app (from Quick Actions UI).
     */
    suspend fun manualThrottle(
        preferences: AppPreferences,
        packageName: String,
        durationMs: Long = 2 * 60 * 60 * 1000L // 2 hours default
    ) {
        // Throttle all non-priority types for this package
        val nonPriorityTypes = NotificationType.entries
            .map { it.name }
            .filter { !PRIORITY_TYPES.contains(it) }

        val throttleUntil = System.currentTimeMillis() + durationMs
        nonPriorityTypes.forEach { typeName ->
            preferences.setPriorityThrottleUntil(packageName, typeName, throttleUntil)
        }
    }

    /**
     * Clears throttle for an app (from Quick Actions UI).
     */
    suspend fun clearThrottle(
        preferences: AppPreferences,
        packageName: String
    ) {
        val allTypes = NotificationType.entries.map { it.name }
        allTypes.forEach { typeName ->
            preferences.clearPriorityThrottleUntil(packageName, typeName)
        }
    }

    /**
     * Checks if any type for this package is currently throttled.
     */
    suspend fun isAppThrottled(
        preferences: AppPreferences,
        packageName: String
    ): Boolean {
        val nonPriorityTypes = NotificationType.entries
            .map { it.name }
            .filter { !PRIORITY_TYPES.contains(it) }

        return nonPriorityTypes.any { typeName ->
            preferences.getPriorityThrottleUntil(packageName, typeName) > System.currentTimeMillis()
        }
    }

    /**
     * Clears burst tracking for a specific groupKey.
     */
    fun clearBurstTracking(packageName: String, typeName: String) {
        val groupKey = "${packageName}:${typeName}"
        burstTracker.remove(groupKey)
    }

    /**
     * Clears all burst tracking (e.g., on service restart).
     */
    fun clearAllBurstTracking() {
        burstTracker.clear()
    }

    private fun getTodayKey(): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        return sdf.format(Date())
    }
}
