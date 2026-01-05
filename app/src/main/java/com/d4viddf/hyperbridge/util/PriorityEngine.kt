package com.d4viddf.hyperbridge.util

import android.content.Context
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.models.NotificationType
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.time.LocalTime
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

    // v0.8.0: Per-type multipliers (lenient vs strict)
    private val TYPE_MULTIPLIERS = mapOf(
        NotificationType.CALL.name to 1.5f,      // More lenient
        NotificationType.TIMER.name to 1.3f,     // More lenient
        NotificationType.NAVIGATION.name to 1.3f, // More lenient
        NotificationType.STANDARD.name to 0.8f   // Stricter
    )

    // v0.8.0: Quiet hours definition (22:00-07:00)
    private const val QUIET_HOURS_START = 22
    private const val QUIET_HOURS_END = 7

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
     * v0.8.0: Added reasonCodes for debugging.
     */
    sealed class Decision {
        data class Allow(val reasonCodes: List<String> = emptyList()) : Decision()
        data class BlockBurst(val reasonCodes: List<String> = emptyList()) : Decision()
        data class BlockThrottle(val reasonCodes: List<String> = emptyList()) : Decision()
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
        val reasonCodes = mutableListOf<String>()

        // If disabled, always allow
        if (!smartPriorityEnabled) {
            reasonCodes.add("DISABLED")
            return Decision.Allow(reasonCodes)
        }

        // Priority types always allowed
        if (PRIORITY_TYPES.contains(typeName)) {
            reasonCodes.add("PRIORITY_TYPE")
            return Decision.Allow(reasonCodes)
        }

        val groupKey = "${packageName}:${typeName}"
        val isQuietHours = isInQuietHours()

        // Check throttle first (with quiet-hours bias)
        if (isThrottled(preferences, packageName, typeName)) {
            reasonCodes.add("THROTTLED")
            if (isQuietHours) reasonCodes.add("QUIET_HOURS")
            return Decision.BlockThrottle(reasonCodes)
        }

        // Check burst (with type multiplier)
        if (isBurst(groupKey, aggressiveness, typeName)) {
            reasonCodes.add("BURST")
            reasonCodes.add("TYPE:${typeName}")
            return Decision.BlockBurst(reasonCodes)
        }

        reasonCodes.add("ALLOWED")
        return Decision.Allow(reasonCodes)
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
     * v0.8.0: Added per-type multipliers.
     */
    private fun isBurst(groupKey: String, aggressiveness: Int, typeName: String): Boolean {
        val timestamps = burstTracker[groupKey] ?: return false
        val now = System.currentTimeMillis()
        val windowMs = getBurstWindowMs(aggressiveness)
        val baseThreshold = getBurstThreshold(aggressiveness)

        // v0.8.0: Apply type multiplier
        val typeMultiplier = TYPE_MULTIPLIERS[typeName] ?: 1.0f
        val adjustedThreshold = (baseThreshold * typeMultiplier).toInt().coerceAtLeast(1)

        val recentCount = timestamps.count { now - it < windowMs }
        return recentCount >= adjustedThreshold
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
     * v0.8.0: Added weighted decay across last 3 days and quiet-hours bias.
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

        // v0.8.0: Weighted decay across last 3 days (1.0/0.6/0.3)
        val yesterday = getTodayKey(-1)
        val twoDaysAgo = getTodayKey(-2)
        
        val countToday = newCount
        val countYesterday = preferences.getPriorityDismissCount(packageName, typeName, yesterday)
        val countTwoDaysAgo = preferences.getPriorityDismissCount(packageName, typeName, twoDaysAgo)
        
        val weightedScore = (countToday * 1.0f) + (countYesterday * 0.6f) + (countTwoDaysAgo * 0.3f)

        // v0.8.0: Quiet-hours bias
        val isQuietHours = isInQuietHours()
        val baseThreshold = getDismissThreshold(aggressiveness)
        
        // During quiet hours: stronger short-term throttle, weaker long-term penalty
        val adjustedThreshold = if (isQuietHours) {
            (baseThreshold * 0.7f).toInt().coerceAtLeast(3) // Lower threshold = faster throttle
        } else {
            baseThreshold
        }

        // Check if we should auto-throttle based on weighted score
        if (weightedScore >= adjustedThreshold) {
            val throttleDuration = if (isQuietHours) {
                // Shorter throttle during quiet hours
                (getThrottleDurationMs(aggressiveness) * 0.5).toLong()
            } else {
                getThrottleDurationMs(aggressiveness)
            }
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

    /**
     * v0.8.0: Added offset parameter for weighted decay calculation.
     */
    private fun getTodayKey(daysOffset: Int = 0): String {
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.US)
        val calendar = java.util.Calendar.getInstance()
        calendar.add(java.util.Calendar.DAY_OF_YEAR, daysOffset)
        return sdf.format(calendar.time)
    }

    /**
     * v0.8.0: Check if current time is in quiet hours (22:00-07:00).
     */
    private fun isInQuietHours(): Boolean {
        return try {
            val now = LocalTime.now()
            val hour = now.hour
            hour >= QUIET_HOURS_START || hour < QUIET_HOURS_END
        } catch (e: Exception) {
            false
        }
    }
}
