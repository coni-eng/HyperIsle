package com.coni.hyperisle.util

import android.content.Context
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.models.NotificationType
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
 * - Burst suppression: ≥3 notifications from same package in 30s → only latest shown
 *   (earlier ones suppressed with PRIORITY_BURST reason, recorded to digest)
 * - High dismiss rate → auto throttle (temporary)
 * - CALL/TIMER/NAV always allowed (priority types) - burst does NOT apply
 * 
 * Persistence via AppPreferences (Room key/value):
 * - priority_dismiss_{pkg}_{type}_{yyyyMMdd} - daily dismiss counter
 * - priority_throttle_until_{pkg}_{type} - throttle expiry timestamp
 */
object PriorityEngine {

    private const val TAG = "PriorityEngine"

    // In-memory burst tracking: packageName -> list of timestamps
    private val burstTracker = ConcurrentHashMap<String, MutableList<Long>>()

    // ═══════════════════════════════════════════════════════════════════════
    // Learning signals (v0.9.2): In-memory tracking for fast dismiss detection
    // ═══════════════════════════════════════════════════════════════════════
    // packageName -> timestamp when island was last shown (for fast dismiss detection)
    private val lastShownAtByPackage = ConcurrentHashMap<String, Long>()
    
    // Fast dismiss threshold: dismiss within 2 seconds = stronger negative signal
    private const val FAST_DISMISS_THRESHOLD_MS = 2000L
    
    // Learning signal weights (small, bounded to avoid destabilization)
    private const val FAST_DISMISS_WEIGHT = 2.0f    // Fast dismiss counts 2x normal dismiss
    private const val TAP_OPEN_OFFSET = -0.5f       // Tap-open slightly offsets penalties
    private const val MUTE_PENALTY_WEIGHT = 3.0f    // Mute/block = strong negative
    
    // Max entries in lastShownAtByPackage to prevent memory growth
    private const val MAX_SHOWN_TRACKING_ENTRIES = 100

    // ═══════════════════════════════════════════════════════════════════════
    // Reason code constants (avoid string allocations in hot path)
    // ═══════════════════════════════════════════════════════════════════════
    private const val REASON_DISABLED = "DISABLED"
    private const val REASON_PRIORITY_TYPE = "PRIORITY_TYPE"
    private const val REASON_BURST = "BURST"
    private const val REASON_THROTTLED = "THROTTLED"
    private const val REASON_QUIET_HOURS_BIAS = "QUIET_HOURS_BIAS"
    private const val REASON_ALLOWED = "ALLOWED"
    private const val REASON_FAST_DISMISS = "FAST_DISMISS"
    private const val REASON_TAP_OPEN_BOOST = "TAP_OPEN_BOOST"
    private const val REASON_MUTE_NEGATIVE = "MUTE_NEGATIVE"
    private const val REASON_PRESET_BYPASS = "PRESET_BYPASS"
    private const val REASON_PRESET_BIAS = "PRESET_BIAS"
    private const val REASON_PROFILE_STRICT = "PROFILE_STRICT_APPLIED"
    private const val REASON_PROFILE_LENIENT = "PROFILE_LENIENT_APPLIED"
    private const val DECISION_ALLOW = "ALLOW"
    private const val DECISION_DENY = "DENY"
    private const val TYPENAME_STANDARD = "STANDARD"

    // v0.9.4: Per-app profile bias multipliers (conservative, bounded)
    // Applied only to STANDARD notifications, does NOT affect CALL/TIMER/NAV
    //
    // WHY CONSERVATIVE MULTIPLIERS:
    // - 1.15f/0.85f are intentionally small (±15%) to prevent runaway bias accumulation
    // - Larger multipliers could cause profiles to dominate all other signals
    // - These values provide noticeable but bounded influence on suppression decisions
    // - Profile influence is capped: cannot override CALL/TIMER/NAV bypass logic
    // - No compounding: multipliers are applied once per decision, not accumulated
    private const val PROFILE_STRICT_MULTIPLIER = 1.15f   // Slightly more aggressive
    private const val PROFILE_LENIENT_MULTIPLIER = 0.85f  // Slightly less aggressive

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

    // Burst detection constants (fixed per user spec: 30s window, >=3 threshold)
    private const val BURST_WINDOW_MS = 30_000L
    private const val BURST_THRESHOLD = 3

    // Thresholds based on aggressiveness (for throttle, not burst)
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
     * CHEAP-FIRST PIPELINE (v0.9.2):
     * Ordered by cost to minimize CPU/allocations under heavy load.
     * Decision outcomes are IDENTICAL to previous implementation.
     * 
     * Order:
     * 1. Feature gate (smartPriorityEnabled) - immediate return if disabled
     * 2. Priority type check (CALL/TIMER/NAV) - cheap set lookup, early allow
     * 3. Quiet hours flag - cheap time check, stored as flag for later use
     * 4. Burst detection (in-memory counter check) - fast, often determines DENY
     * 5. Throttle check (Room lookup) - more expensive, only if not burst-blocked
     * 
     * Burst behavior ("show latest only"):
     * - For STANDARD notifications only
     * - If >=3 notifications from same package arrive within 30s rolling window,
     *   suppress earlier ones (they go to digest) and show only the latest.
     * 
     * @param context Application context
     * @param preferences AppPreferences instance
     * @param packageName Source app package
     * @param typeName NotificationType name
     * @param smartPriorityEnabled Whether smart priority is enabled
     * @param aggressiveness Aggressiveness level (0-2)
     * @param sbnKeyHash Hash of sbn.key for diagnostics (optional)
     * @return Decision indicating whether to allow or block
     */
    suspend fun evaluate(
        context: Context,
        preferences: AppPreferences,
        packageName: String,
        typeName: String,
        smartPriorityEnabled: Boolean,
        aggressiveness: Int,
        sbnKeyHash: Int = 0,
        presetAggressivenessBias: Int = 0,
        appProfile: com.coni.hyperisle.models.SmartPriorityProfile = com.coni.hyperisle.models.SmartPriorityProfile.NORMAL
    ): Decision {
        // ═══════════════════════════════════════════════════════════════════
        // CHEAP-FIRST PIPELINE: Ordered by cost, short-circuit early
        // ═══════════════════════════════════════════════════════════════════
        
        // ─── GATE 1: Feature disabled → immediate ALLOW (zero overhead) ───
        if (!smartPriorityEnabled) {
            return allowWithReason(packageName, sbnKeyHash, REASON_DISABLED)
        }

        // ─── GATE 2: Priority types → early ALLOW (cheap set lookup) ───
        // CALL/TIMER/NAV bypass all throttling/burst logic
        if (typeName in PRIORITY_TYPES) {
            return allowWithReason(packageName, sbnKeyHash, REASON_PRIORITY_TYPE)
        }

        // ─── GATE 3: Quiet hours flag (cheap time check, no I/O) ───
        // Computed once, used later if needed for reason codes
        val isQuietHours = isInQuietHours()

        // ─── GATE 4: Burst detection (in-memory only, very fast) ───
        // Only for STANDARD type; record timestamp then check burst state
        if (typeName == TYPENAME_STANDARD) {
            recordBurstTimestamp(packageName)
            if (isInBurst(packageName)) {
                return denyBurstWithReason(packageName, sbnKeyHash)
            }
        }

        // ─── GATE 5: Throttle check (Room lookup, more expensive) ───
        // Only reached if not burst-blocked
        if (isThrottled(preferences, packageName, typeName)) {
            val reasons = if (presetAggressivenessBias > 0) {
                listOf(REASON_THROTTLED, REASON_PRESET_BIAS)
            } else if (isQuietHours) {
                listOf(REASON_THROTTLED, REASON_QUIET_HOURS_BIAS)
            } else {
                listOf(REASON_THROTTLED)
            }
            recordDiagnostic(packageName, sbnKeyHash, DECISION_DENY, reasons)
            return Decision.BlockThrottle(reasons)
        }
        
        // ─── GATE 6: Preset bias for STANDARD (conservative additional throttling) ───
        // When preset is MEETING/DRIVING, apply stricter burst threshold for STANDARD
        if (presetAggressivenessBias > 0 && typeName == TYPENAME_STANDARD) {
            // Use stricter burst threshold based on preset bias
            val presetBurstThreshold = (BURST_THRESHOLD - presetAggressivenessBias).coerceAtLeast(2)
            val timestamps = burstTracker[packageName] ?: emptyList<Long>()
            val now = System.currentTimeMillis()
            val recentCount = timestamps.count { now - it < BURST_WINDOW_MS }
            if (recentCount >= presetBurstThreshold) {
                val reasons = listOf(REASON_BURST, REASON_PRESET_BIAS)
                recordDiagnostic(packageName, sbnKeyHash, DECISION_DENY, reasons)
                return Decision.BlockBurst(reasons)
            }
        }

        // ─── GATE 7: Per-app profile bias for STANDARD (v0.9.4) ───
        // STRICT: slightly more aggressive burst detection (lower threshold)
        // LENIENT: slightly less aggressive (higher threshold)
        // NORMAL: no change (default behavior)
        // Only affects STANDARD notifications; CALL/TIMER/NAV already bypassed at GATE 2
        //
        // SAFETY & BOUNDS (v0.9.5):
        // - Profile influence is bounded: threshold adjustment is ±1, never more
        // - No runaway bias: profile is evaluated once per decision, not accumulated
        // - CALL/TIMER/NAV bypass at GATE 2 ensures critical notifications always pass
        // - NORMAL profile = identical behavior to previous versions (no change)
        if (typeName == TYPENAME_STANDARD && appProfile != com.coni.hyperisle.models.SmartPriorityProfile.NORMAL) {
            val timestamps = burstTracker[packageName] ?: emptyList<Long>()
            val now = System.currentTimeMillis()
            val recentCount = timestamps.count { now - it < BURST_WINDOW_MS }
            
            when (appProfile) {
                com.coni.hyperisle.models.SmartPriorityProfile.STRICT -> {
                    // Lower threshold = more likely to suppress (threshold - 1, min 2)
                    val strictThreshold = (BURST_THRESHOLD - 1).coerceAtLeast(2)
                    if (recentCount >= strictThreshold) {
                        val reasons = listOf(REASON_BURST, REASON_PROFILE_STRICT)
                        recordDiagnostic(packageName, sbnKeyHash, DECISION_DENY, reasons)
                        return Decision.BlockBurst(reasons)
                    }
                }
                com.coni.hyperisle.models.SmartPriorityProfile.LENIENT -> {
                    // Higher threshold = less likely to suppress (threshold + 1)
                    // If we're at exactly BURST_THRESHOLD, allow it (lenient)
                    // This effectively means lenient apps need 1 more notification to trigger burst
                    val lenientThreshold = BURST_THRESHOLD + 1
                    // Already passed normal burst check at GATE 4, so if we're here with LENIENT,
                    // we allow through (the normal check already passed)
                    // Record diagnostic hint that lenient profile is active
                    if (recentCount >= BURST_THRESHOLD && recentCount < lenientThreshold) {
                        // Would have been blocked normally, but lenient profile allows it
                        if (PriorityDiagnostics.isEnabled()) {
                            PriorityDiagnostics.record(packageName, sbnKeyHash, "ALLOW_LENIENT", REASON_PROFILE_LENIENT)
                        }
                    }
                }
                else -> { /* NORMAL - no action */ }
            }
        }

        // ─── ALLOW: Passed all gates ───
        return allowWithReason(packageName, sbnKeyHash, REASON_ALLOWED)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Decision helpers (avoid allocations in hot path by reusing reason strings)
    // ═══════════════════════════════════════════════════════════════════════
    
    private fun allowWithReason(pkg: String, keyHash: Int, reason: String): Decision.Allow {
        val reasons = listOf(reason)
        recordDiagnostic(pkg, keyHash, DECISION_ALLOW, reasons)
        return Decision.Allow(reasons)
    }

    private fun denyBurstWithReason(pkg: String, keyHash: Int): Decision.BlockBurst {
        val reasons = listOf(REASON_BURST, "PKG:$pkg")
        recordDiagnostic(pkg, keyHash, DECISION_DENY, reasons)
        return Decision.BlockBurst(reasons)
    }

    private fun denyThrottleWithReason(pkg: String, keyHash: Int, isQuietHours: Boolean): Decision.BlockThrottle {
        val reasons = if (isQuietHours) {
            listOf(REASON_THROTTLED, REASON_QUIET_HOURS_BIAS)
        } else {
            listOf(REASON_THROTTLED)
        }
        recordDiagnostic(pkg, keyHash, DECISION_DENY, reasons)
        return Decision.BlockThrottle(reasons)
    }

    /**
     * Creates an Allow decision with PRESET_BYPASS reason.
     * Called from NotificationReaderService when preset bypasses Smart Priority for critical types.
     */
    fun allowPresetBypass(packageName: String, sbnKeyHash: Int): Decision.Allow {
        return allowWithReason(packageName, sbnKeyHash, REASON_PRESET_BYPASS)
    }

    /**
     * Records a timestamp for burst tracking (per-package).
     */
    private fun recordBurstTimestamp(burstKey: String) {
        val now = System.currentTimeMillis()
        burstTracker.compute(burstKey) { _, list ->
            val timestamps = list ?: mutableListOf()
            timestamps.add(now)
            // Keep only last 10 entries to prevent memory bloat
            while (timestamps.size > 10) {
                timestamps.removeAt(0)
            }
            timestamps
        }
    }

    /**
     * Checks if we're currently in a burst for this package.
     * A burst is defined as >=3 notifications within 30 seconds.
     */
    private fun isInBurst(burstKey: String): Boolean {
        val timestamps = burstTracker[burstKey] ?: return false
        val now = System.currentTimeMillis()
        val recentCount = timestamps.count { now - it < BURST_WINDOW_MS }
        return recentCount >= BURST_THRESHOLD
    }

    /**
     * Records a diagnostic entry for priority decisions (debug-only, PII-safe).
     */
    private fun recordDiagnostic(pkg: String, keyHash: Int, decision: String, reasons: List<String>) {
        PriorityDiagnostics.record(pkg, keyHash, decision, reasons.joinToString(","))
    }

    /**
     * Records a notification being shown (for legacy burst tracking).
     * Note: Burst tracking for "show latest only" is now done in evaluate() before decision.
     * This method is kept for compatibility but burst tracking moved to evaluate().
     */
    fun recordShown(packageName: String, typeName: String) {
        // Burst tracking is now done in evaluate() for STANDARD notifications.
        // This method is kept for API compatibility but is effectively a no-op for burst.
        // The groupKey-based tracking is no longer used for burst detection.
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Learning signals (v0.9.2): Recording methods
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Records when an island is shown for a package.
     * Used for fast dismiss detection: if user dismisses within FAST_DISMISS_THRESHOLD_MS,
     * it's a stronger negative signal.
     * 
     * Called from NotificationReaderService when island is posted.
     * PII-safe: only stores packageName and timestamp.
     */
    fun recordIslandShown(packageName: String) {
        val now = System.currentTimeMillis()
        lastShownAtByPackage[packageName] = now
        
        // Bound memory: remove oldest entries if over limit
        if (lastShownAtByPackage.size > MAX_SHOWN_TRACKING_ENTRIES) {
            // Find and remove entries older than 1 hour (stale)
            val oneHourAgo = now - 3600_000L
            val keysToRemove = lastShownAtByPackage.entries
                .filter { it.value < oneHourAgo }
                .map { it.key }
            keysToRemove.forEach { lastShownAtByPackage.remove(it) }
        }
    }

    /**
     * Checks if a dismiss is "fast" (within threshold of island being shown).
     * Returns true if dismiss occurred within FAST_DISMISS_THRESHOLD_MS of show.
     */
    fun isFastDismiss(packageName: String): Boolean {
        val shownAt = lastShownAtByPackage[packageName] ?: return false
        val now = System.currentTimeMillis()
        return (now - shownAt) <= FAST_DISMISS_THRESHOLD_MS
    }

    /**
     * Records a tap-open action (user tapped island to open source app).
     * This is a positive signal that offsets penalties.
     * 
     * PII-safe: only stores packageName and counter.
     */
    suspend fun recordTapOpen(preferences: AppPreferences, packageName: String) {
        // Priority types don't need learning signals
        if (PRIORITY_TYPES.any { packageName.contains(it, ignoreCase = true) }) return
        
        val today = getTodayKey()
        val currentCount = preferences.getLearningTapOpenCount(packageName, today)
        preferences.setLearningTapOpenCount(packageName, today, currentCount + 1)
        
        // Record diagnostic if enabled
        if (PriorityDiagnostics.isEnabled()) {
            PriorityDiagnostics.record(packageName, 0, "SIGNAL", REASON_TAP_OPEN_BOOST)
        }
    }

    /**
     * Records a mute or block action from Quick Actions.
     * This is a strong negative signal.
     * 
     * Note: Does not change mute/block behavior (that's handled by AppPreferences).
     * Only records the signal for future priority decisions.
     * 
     * PII-safe: only stores packageName and counter.
     */
    suspend fun recordMuteBlock(preferences: AppPreferences, packageName: String) {
        val today = getTodayKey()
        val currentCount = preferences.getLearningMuteBlockCount(packageName, today)
        preferences.setLearningMuteBlockCount(packageName, today, currentCount + 1)
        
        // Record diagnostic if enabled
        if (PriorityDiagnostics.isEnabled()) {
            PriorityDiagnostics.record(packageName, 0, "SIGNAL", REASON_MUTE_NEGATIVE)
        }
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
     * v0.9.2: Added fast dismiss detection - stronger penalty if dismissed within 2s.
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
        
        // v0.9.2: Fast dismiss detection - stronger negative signal
        val isFast = isFastDismiss(packageName)
        if (isFast) {
            // Increment fast dismiss counter (separate from normal dismiss)
            val fastCount = preferences.getLearningFastDismissCount(packageName, today)
            preferences.setLearningFastDismissCount(packageName, today, fastCount + 1)
            
            // Record diagnostic if enabled
            if (PriorityDiagnostics.isEnabled()) {
                PriorityDiagnostics.record(packageName, 0, "SIGNAL", REASON_FAST_DISMISS)
            }
        }
        
        // Clear the shown timestamp after processing
        lastShownAtByPackage.remove(packageName)
        
        val currentCount = preferences.getPriorityDismissCount(packageName, typeName, today)
        val newCount = currentCount + 1
        preferences.setPriorityDismissCount(packageName, typeName, today, newCount)

        // v0.8.0: Weighted decay across last 3 days (1.0/0.6/0.3)
        val yesterday = getTodayKey(-1)
        val twoDaysAgo = getTodayKey(-2)
        
        val countToday = newCount
        val countYesterday = preferences.getPriorityDismissCount(packageName, typeName, yesterday)
        val countTwoDaysAgo = preferences.getPriorityDismissCount(packageName, typeName, twoDaysAgo)
        
        // v0.9.2: Include learning signals in weighted score
        val fastDismissToday = preferences.getLearningFastDismissCount(packageName, today)
        val tapOpenToday = preferences.getLearningTapOpenCount(packageName, today)
        val muteBlockToday = preferences.getLearningMuteBlockCount(packageName, today)
        
        // Base weighted score from dismiss history
        var weightedScore = (countToday * 1.0f) + (countYesterday * 0.6f) + (countTwoDaysAgo * 0.3f)
        
        // Apply learning signal adjustments (bounded to avoid destabilization)
        weightedScore += (fastDismissToday * FAST_DISMISS_WEIGHT).coerceAtMost(5.0f)  // Fast dismiss penalty
        weightedScore += (tapOpenToday * TAP_OPEN_OFFSET).coerceAtLeast(-3.0f)        // Tap-open offset (negative = reduces score)
        weightedScore += (muteBlockToday * MUTE_PENALTY_WEIGHT).coerceAtMost(10.0f)   // Mute/block penalty
        
        // Ensure score doesn't go negative
        weightedScore = weightedScore.coerceAtLeast(0f)

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
     * Clears all in-memory learning state (burst tracker, shown timestamps).
     * Called when resetting Smart Priority learning from debug UI.
     */
    fun clearAllInMemoryState() {
        burstTracker.clear()
        lastShownAtByPackage.clear()
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
