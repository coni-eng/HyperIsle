package com.coni.hyperisle.overlay.engine

import android.util.Log
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.overlay.features.IslandFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Central decision engine for island management.
 * 
 * Responsibilities:
 * - Priority-based preemption using ActiveIsland priority table
 * - Route tekilleştirme (same key: MIUI_BRIDGE vs APP_OVERLAY - only one wins)
 * - State management for active island
 * - Guards (call ended detection, stale event filtering)
 * - Stack for resume-able states (Navigation, Notification, Timer)
 * - Notification spam debounce (same key within 500ms = update, not new)
 * 
 * PRIORITY TABLE (see ActiveIsland.Companion):
 * - Incoming Call: 100 (sticky, preempt all)
 * - Ongoing Call: 90 (sticky, preempt nav/notif/timer)
 * - Alarm ringing: 80 (sticky)
 * - Timer finished/ringing: 70 (sticky)
 * - Navigation active guidance: 60 (sticky)
 * - Navigation moment: 55 (non-sticky, minVisible 1500)
 * - Notification heads-up/important: 40 (big->mini->dismiss, minVisible 2500)
 * - Notification normal: 30 (mini or short big, minVisible 1500-2000)
 * - Media (system/hyperos): 20 (route SYSTEM_MEDIA)
 * - Fallback/Progress/Standard: 10
 * 
 * DETERMINISM (simultaneous events):
 * 1) priority (higher wins)
 * 2) sticky (sticky wins)
 * 3) timestamp (newer wins)
 * 4) featureId alphabetical (last resort)
 * 
 * CRITICAL: Only ONE island is active at a time. This coordinator ensures that.
 */
class IslandCoordinator(
    private val features: List<IslandFeature>,
    private val routeDecider: RouteDecider,
    private val onActiveIslandChanged: (ActiveIsland?) -> Unit
) {
    companion object {
        private const val TAG = "IslandCoordinator"
        
        // Dedupe TTLs
        private const val USER_DISMISS_DEDUPE_TTL_MS = 60_000L
        private const val CALL_END_COOLDOWN_MS = 3_000L
        private const val REMOVED_NOTIFICATION_TTL_MS = 30_000L
        
        // Notification spam debounce: same key within this window = update, not new big animation
        private const val NOTIFICATION_SPAM_DEBOUNCE_MS = 500L
        
        // Stack size limits
        private const val MAX_STACK_SIZE = 3
    }

    // Current active island
    private val _activeIsland = MutableStateFlow<ActiveIsland?>(null)
    val activeIsland: StateFlow<ActiveIsland?> = _activeIsland.asStateFlow()

    // Dedupe tracking
    private val userDismissedKeys = ConcurrentHashMap<String, Long>()
    private val removedNotificationKeys = ConcurrentHashMap<String, Long>()
    
    // Notification spam debounce: key -> last event timestamp
    private val lastNotificationEventTs = ConcurrentHashMap<String, Long>()
    
    // Call state tracking
    @Volatile
    private var lastCallEndTs: Long = 0L
    
    // Route tracking for tekilleştirme
    private val activeRoutes = ConcurrentHashMap<String, IslandRoute>()
    
    // Stack for resume-able states (Navigation, Notification, Timer)
    // When high-priority island finishes, resume from stack
    private val resumeStack = mutableListOf<ActiveIsland>()

    /**
     * Process an incoming event.
     * This is the main entry point from the event bus.
     */
    fun onEvent(event: IslandEvent, nowMs: Long = System.currentTimeMillis()) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "EVT=COORD_EVENT type=${event::class.simpleName} key=${event.notificationKey.hashCode()}")
        }

        // Handle dismiss events
        when (event) {
            is IslandEvent.Dismiss -> {
                handleDismiss(event.notificationKey, event.reason, nowMs)
                return
            }
            is IslandEvent.DismissAll -> {
                handleDismissAll(event.reason, nowMs)
                return
            }
            is IslandEvent.UserDismiss -> {
                handleUserDismiss(event.notificationKey, event.reason, nowMs)
                return
            }
            is IslandEvent.CallEnded -> {
                handleCallEnded(event.notificationKey, nowMs)
                return
            }
            else -> {}
        }

        // Check guards
        if (!passesGuards(event, nowMs)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "EVT=COORD_GUARD_BLOCK key=${event.notificationKey.hashCode()}")
            }
            return
        }

        // Find feature that can handle this event
        val feature = features.find { it.canHandle(event) }
        if (feature == null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "EVT=COORD_NO_FEATURE type=${event::class.simpleName}")
            }
            return
        }

        // Reduce state
        val currentIsland = _activeIsland.value
        val prevState = if (currentIsland?.featureId == feature.id && 
                           currentIsland.notificationKey == event.notificationKey) {
            currentIsland.state
        } else {
            null
        }
        
        val newState = feature.reduce(prevState, event, nowMs)
        if (newState == null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "EVT=COORD_REDUCE_NULL feature=${feature.id}")
            }
            return
        }

        // Calculate priority and route
        val priority = feature.priority(newState)
        val route = routeDecider.decideRoute(event, feature.route(newState))
        val policy = feature.policy(newState)

        // Route tekilleştirme
        val existingRoute = activeRoutes[event.notificationKey]
        if (existingRoute != null && existingRoute != route && route.shouldRender()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "EVT=ROUTE_DECISION key=${event.notificationKey.hashCode()} route=$route suppressed=true existingRoute=$existingRoute")
            }
            // Same key, different route - suppress if existing route is active
            if (currentIsland?.notificationKey == event.notificationKey) {
                return
            }
        }

        // Check notification spam debounce
        val isSpamUpdate = if (event is IslandEvent.Notification) {
            val lastTs = lastNotificationEventTs[event.notificationKey]
            val isSpam = lastTs != null && (nowMs - lastTs) < NOTIFICATION_SPAM_DEBOUNCE_MS
            lastNotificationEventTs[event.notificationKey] = nowMs
            if (isSpam && BuildConfig.DEBUG) {
                Log.d(TAG, "EVT=NOTIF_SPAM_UPDATE key=${event.notificationKey.hashCode()} elapsed=${nowMs - (lastTs ?: 0)}ms")
            }
            isSpam
        } else false

        // Check preemption with determinism rules
        if (currentIsland != null && currentIsland.notificationKey != event.notificationKey) {
            val shouldPreempt = compareForPreemption(
                newPriority = priority,
                newSticky = policy.sticky,
                newTimestamp = nowMs,
                newFeatureId = feature.id,
                currentPriority = currentIsland.priority,
                currentSticky = currentIsland.policy.sticky,
                currentTimestamp = currentIsland.activeSinceMs,
                currentFeatureId = currentIsland.featureId
            )
            
            if (!shouldPreempt) {
                // Lower priority - add to stack if resume-able
                if (isResumeable(feature.id)) {
                    addToResumeStack(ActiveIsland(
                        featureId = feature.id,
                        notificationKey = event.notificationKey,
                        packageName = event.packageName,
                        state = newState,
                        route = route,
                        policy = policy,
                        priority = priority,
                        activeSinceMs = nowMs
                    ))
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "EVT=PREEMPT_BLOCKED from=${feature.id} to=${currentIsland.featureId} reason=DETERMINISM newPri=$priority activePri=${currentIsland.priority}")
                }
                return
            } else {
                // Higher priority - preempt, push current to stack if resume-able
                if (isResumeable(currentIsland.featureId)) {
                    addToResumeStack(currentIsland)
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "EVT=PREEMPT from=${currentIsland.featureId} to=${feature.id} reason=PRIORITY")
                }
            }
        }

        // Determine if this is an update to existing island (preserve UI state) or new
        val preserveUiState = currentIsland?.notificationKey == event.notificationKey

        // Create new active island
        val newIsland = ActiveIsland(
            featureId = feature.id,
            notificationKey = event.notificationKey,
            packageName = event.packageName,
            state = newState,
            route = route,
            policy = policy,
            priority = priority,
            activeSinceMs = if (preserveUiState) currentIsland!!.activeSinceMs else nowMs,
            isExpanded = if (preserveUiState) currentIsland!!.isExpanded else false,
            isCollapsed = if (preserveUiState) currentIsland!!.isCollapsed else false,
            isReplying = if (preserveUiState) currentIsland!!.isReplying else false
        )

        // Update route tracking
        if (route.shouldRender()) {
            activeRoutes[event.notificationKey] = route
        }

        // Emit new state
        _activeIsland.value = newIsland
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "EVT=COORD_ACTIVE feature=${feature.id} route=$route reason=EVENT_PROCESSED isSpamUpdate=$isSpamUpdate")
        }
        
        onActiveIslandChanged(newIsland)
    }

    /**
     * Update UI state (expand/collapse/reply) without re-processing event.
     */
    fun updateUiState(
        isExpanded: Boolean? = null,
        isCollapsed: Boolean? = null,
        isReplying: Boolean? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val current = _activeIsland.value ?: return
        
        val updated = current.copy(
            isExpanded = isExpanded ?: current.isExpanded,
            isCollapsed = isCollapsed ?: current.isCollapsed,
            isReplying = isReplying ?: current.isReplying,
            collapsedAtMs = if (isCollapsed == true && current.collapsedAtMs == null) nowMs else current.collapsedAtMs
        )
        
        _activeIsland.value = updated
        onActiveIslandChanged(updated)
    }

    /**
     * Check if minimum visible time has elapsed for dismiss.
     */
    fun canDismissNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val current = _activeIsland.value ?: return true
        return current.canDismiss(nowMs)
    }

    /**
     * Get remaining minimum visible time.
     */
    fun remainingMinVisibleMs(nowMs: Long = System.currentTimeMillis()): Long {
        val current = _activeIsland.value ?: return 0L
        return current.remainingMinVisibleMs(nowMs)
    }

    // ==================== GUARDS ====================

    private fun passesGuards(event: IslandEvent, nowMs: Long): Boolean {
        val key = event.notificationKey

        // Guard: User dismissed - dedupe for TTL
        userDismissedKeys[key]?.let { dismissTime ->
            val elapsed = nowMs - dismissTime
            if (elapsed < USER_DISMISS_DEDUPE_TTL_MS) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "EVT=GUARD_USER_DISMISSED key=${key.hashCode()} ttlRemaining=${USER_DISMISS_DEDUPE_TTL_MS - elapsed}ms")
                }
                return false
            } else {
                userDismissedKeys.remove(key)
            }
        }

        // Guard: Removed notification - dedupe for TTL
        removedNotificationKeys[key]?.let { removedTime ->
            val elapsed = nowMs - removedTime
            if (elapsed < REMOVED_NOTIFICATION_TTL_MS) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "EVT=GUARD_NOTIF_REMOVED key=${key.hashCode()} ttlRemaining=${REMOVED_NOTIFICATION_TTL_MS - elapsed}ms")
                }
                return false
            } else {
                removedNotificationKeys.remove(key)
            }
        }

        // Guard: Call cooldown - ignore stale MIUI bridge events after call ends
        if (event is IslandEvent.CallEvent && event !is IslandEvent.IncomingCall) {
            if (lastCallEndTs > 0) {
                val elapsed = nowMs - lastCallEndTs
                if (elapsed < CALL_END_COOLDOWN_MS) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "EVT=GUARD_CALL_COOLDOWN key=${key.hashCode()} cooldownRemaining=${CALL_END_COOLDOWN_MS - elapsed}ms")
                    }
                    return false
                }
            }
        }

        return true
    }

    // ==================== DISMISS HANDLERS ====================

    private fun handleDismiss(key: String, reason: String, nowMs: Long) {
        val current = _activeIsland.value
        if (current?.notificationKey == key) {
            // Check minimum visible time
            if (!current.canDismiss(nowMs)) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "EVT=DISMISS_DELAYED key=${key.hashCode()} remainingMs=${current.remainingMinVisibleMs(nowMs)}")
                }
                // Could schedule delayed dismiss here
                return
            }
            
            clearActiveIsland(reason)
        }
        
        // Track removed notification
        removedNotificationKeys[key] = nowMs
        activeRoutes.remove(key)
    }

    private fun handleDismissAll(reason: String, nowMs: Long) {
        clearActiveIsland(reason)
        activeRoutes.clear()
    }

    private fun handleUserDismiss(key: String, reason: String, nowMs: Long) {
        // Track user dismiss for dedupe
        userDismissedKeys[key] = nowMs
        
        val current = _activeIsland.value
        if (current?.notificationKey == key) {
            _activeIsland.value = current.copy(userDismissed = true, userDismissedAtMs = nowMs)
            clearActiveIsland(reason)
        }
        
        activeRoutes.remove(key)
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "EVT=USER_DISMISS key=${key.hashCode()} reason=$reason ttl=${USER_DISMISS_DEDUPE_TTL_MS}ms")
        }
    }

    private fun handleCallEnded(key: String, nowMs: Long) {
        lastCallEndTs = nowMs
        
        val current = _activeIsland.value
        if (current?.notificationKey == key || current?.featureId == "call") {
            clearActiveIsland("CALL_ENDED")
        }
        
        // Track for dedupe
        removedNotificationKeys[key] = nowMs
        activeRoutes.remove(key)
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "EVT=CALL_ENDED_RESET done=true key=${key.hashCode()} cooldown=${CALL_END_COOLDOWN_MS}ms")
        }
    }

    private fun clearActiveIsland(reason: String) {
        val prev = _activeIsland.value
        
        if (BuildConfig.DEBUG && prev != null) {
            Log.d(TAG, "EVT=COORD_CLEAR feature=${prev.featureId} reason=$reason stackSize=${resumeStack.size}")
        }
        
        // Try to resume from stack
        val resumed = resumeFromStack()
        if (resumed != null) {
            _activeIsland.value = resumed
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "EVT=COORD_RESUME feature=${resumed.featureId} priority=${resumed.priority}")
            }
            onActiveIslandChanged(resumed)
        } else {
            _activeIsland.value = null
            onActiveIslandChanged(null)
        }
    }

    // ==================== PREEMPTION DETERMINISM ====================

    /**
     * Compare two islands for preemption using determinism rules:
     * 1) priority (higher wins)
     * 2) sticky (sticky wins over non-sticky)
     * 3) timestamp (newer wins)
     * 4) featureId alphabetical (last resort)
     * 
     * @return true if new should preempt current
     */
    private fun compareForPreemption(
        newPriority: Int,
        newSticky: Boolean,
        newTimestamp: Long,
        newFeatureId: String,
        currentPriority: Int,
        currentSticky: Boolean,
        currentTimestamp: Long,
        currentFeatureId: String
    ): Boolean {
        // 1) Priority comparison (higher wins)
        if (newPriority != currentPriority) {
            return newPriority > currentPriority
        }
        
        // 2) Sticky comparison (sticky wins over non-sticky)
        if (newSticky != currentSticky) {
            return newSticky
        }
        
        // 3) Timestamp comparison (newer wins)
        if (newTimestamp != currentTimestamp) {
            return newTimestamp > currentTimestamp
        }
        
        // 4) FeatureId alphabetical (last resort - alphabetically first wins for determinism)
        return newFeatureId < currentFeatureId
    }

    // ==================== STACK MANAGEMENT ====================

    /**
     * Check if a feature is resume-able (can be pushed to stack).
     * Resume-able: Navigation, Notification (last 1), Timer
     */
    private fun isResumeable(featureId: String): Boolean {
        return featureId in setOf("navigation", "notification", "timer")
    }

    /**
     * Add island to resume stack with size limits.
     * For notifications, only keep the latest one.
     */
    private fun addToResumeStack(island: ActiveIsland) {
        // For notifications, remove any existing notification from stack (keep only 1)
        if (island.featureId == "notification") {
            resumeStack.removeAll { it.featureId == "notification" }
        }
        
        // Remove if same key already in stack
        resumeStack.removeAll { it.notificationKey == island.notificationKey }
        
        // Add to stack
        resumeStack.add(island)
        
        // Enforce max stack size (remove oldest if exceeded)
        while (resumeStack.size > MAX_STACK_SIZE) {
            val removed = resumeStack.removeAt(0)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "EVT=STACK_OVERFLOW_REMOVE feature=${removed.featureId}")
            }
        }
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "EVT=STACK_PUSH feature=${island.featureId} stackSize=${resumeStack.size}")
        }
    }

    /**
     * Resume highest priority island from stack.
     * Returns null if stack is empty.
     */
    private fun resumeFromStack(): ActiveIsland? {
        if (resumeStack.isEmpty()) return null
        
        // Find highest priority in stack
        val best = resumeStack.maxByOrNull { it.priority } ?: return null
        resumeStack.remove(best)
        
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "EVT=STACK_POP feature=${best.featureId} remainingStackSize=${resumeStack.size}")
        }
        
        return best
    }

    // ==================== CLEANUP ====================

    /**
     * Clean up expired dedupe entries.
     * Call periodically (e.g., every 30 seconds).
     */
    fun cleanupExpiredEntries(nowMs: Long = System.currentTimeMillis()) {
        userDismissedKeys.entries.removeIf { nowMs - it.value >= USER_DISMISS_DEDUPE_TTL_MS }
        removedNotificationKeys.entries.removeIf { nowMs - it.value >= REMOVED_NOTIFICATION_TTL_MS }
    }

    /**
     * Force clear all state (service destroyed).
     */
    fun clearAll() {
        _activeIsland.value = null
        userDismissedKeys.clear()
        removedNotificationKeys.clear()
        lastNotificationEventTs.clear()
        activeRoutes.clear()
        resumeStack.clear()
        lastCallEndTs = 0L
    }
}

/**
 * Interface for route decision logic.
 * Separated to allow different strategies (MIUI bridge preference, force overlay, etc.)
 */
interface RouteDecider {
    fun decideRoute(event: IslandEvent, featurePreference: IslandRoute): IslandRoute
}

/**
 * Default route decider - uses feature preference.
 */
class DefaultRouteDecider(
    private val useMiuiBridge: () -> Boolean = { false },
    private val forceOverlay: () -> Boolean = { false }
) : RouteDecider {
    override fun decideRoute(event: IslandEvent, featurePreference: IslandRoute): IslandRoute {
        // Force overlay mode overrides everything
        if (forceOverlay()) {
            return if (featurePreference == IslandRoute.NONE) IslandRoute.NONE else IslandRoute.APP_OVERLAY
        }
        
        // MIUI bridge preference
        if (useMiuiBridge() && featurePreference == IslandRoute.APP_OVERLAY) {
            return IslandRoute.MIUI_BRIDGE
        }
        
        return featurePreference
    }
}
