package com.coni.hyperisle.overlay.engine

/**
 * Represents the currently active island state.
 * This is the output of the coordinator's decision-making.
 */
data class ActiveIsland(
    /**
     * Unique identifier for the active feature.
     */
    val featureId: String,

    /**
     * The notification key associated with this island.
     */
    val notificationKey: String,

    /**
     * Package name of the source app.
     */
    val packageName: String,

    /**
     * Feature-specific state object.
     * Type depends on the feature (CallState, NotificationState, etc.)
     */
    val state: Any?,

    /**
     * Current routing decision.
     */
    val route: IslandRoute,

    /**
     * Current policy configuration.
     */
    val policy: IslandPolicy,

    /**
     * Priority level for preemption decisions.
     * Higher = more important. Range: 0-1000.
     */
    val priority: Int,

    /**
     * Timestamp when this island became active.
     */
    val activeSinceMs: Long = System.currentTimeMillis(),

    /**
     * Whether the island is currently expanded.
     */
    val isExpanded: Boolean = false,

    /**
     * Whether the island is in collapsed/mini mode.
     */
    val isCollapsed: Boolean = false,

    /**
     * Whether inline reply is currently active.
     */
    val isReplying: Boolean = false,

    /**
     * Timestamp when collapse timer started (for auto-dismiss).
     */
    val collapsedAtMs: Long? = null,

    /**
     * Whether this island was user-dismissed (for dedupe tracking).
     */
    val userDismissed: Boolean = false,

    /**
     * User dismiss timestamp for TTL-based dedupe.
     */
    val userDismissedAtMs: Long? = null
) {
    /**
     * Calculate remaining minimum visible time.
     */
    fun remainingMinVisibleMs(nowMs: Long = System.currentTimeMillis()): Long {
        val elapsed = nowMs - activeSinceMs
        val remaining = policy.minVisibleMs - elapsed
        return if (remaining > 0) remaining else 0L
    }

    /**
     * Check if minimum visible time has elapsed.
     */
    fun canDismiss(nowMs: Long = System.currentTimeMillis()): Boolean {
        return remainingMinVisibleMs(nowMs) <= 0L
    }

    /**
     * Check if this island should suppress another.
     */
    fun preempts(other: ActiveIsland): Boolean {
        return priority > other.priority
    }

    /**
     * Create updated copy with new state.
     */
    fun withState(newState: Any?): ActiveIsland = copy(state = newState)

    /**
     * Create expanded version.
     */
    fun expanded(): ActiveIsland = copy(isExpanded = true, isCollapsed = false)

    /**
     * Create collapsed version.
     */
    fun collapsed(nowMs: Long = System.currentTimeMillis()): ActiveIsland = 
        copy(isExpanded = false, isCollapsed = true, collapsedAtMs = nowMs)

    /**
     * Create replying version.
     */
    fun replying(isReplying: Boolean): ActiveIsland = copy(
        isReplying = isReplying,
        policy = if (isReplying) policy.copy(needsFocus = true) else policy.copy(needsFocus = false)
    )

    companion object {
        // ==================== PRIORITY TABLE (SINGLE SOURCE OF TRUTH) ====================
        // Higher = more important. Used for preemption decisions.
        // CRITICAL: This table is the authoritative source for priority values.
        // 
        // PREEMPTION RULES:
        // - Higher priority preempts lower priority
        // - Same priority: sticky wins over non-sticky
        // - Same priority + sticky: newer timestamp wins
        // - Last resort: featureId alphabetical order
        //
        // STACK RULES:
        // - Only resume-able states go on stack: Navigation, last 1 Notification, Timer
        // - When preempting island finishes, stack top (highest priority) resumes
        
        // Incoming call: highest priority, sticky, preempts all
        const val PRIORITY_INCOMING_CALL = 100
        
        // Ongoing call: sticky, preempts nav/notif/timer
        const val PRIORITY_ONGOING_CALL = 90
        
        // Alarm ringing: sticky
        const val PRIORITY_ALARM = 80
        
        // Timer finished/ringing: sticky
        const val PRIORITY_TIMER_RINGING = 70
        
        // Navigation active guidance: sticky
        const val PRIORITY_NAVIGATION_ACTIVE = 60
        
        // Navigation moment: non-sticky, minVisible 1500ms
        const val PRIORITY_NAVIGATION_MOMENT = 55
        
        // Notification heads-up/important: big->mini->dismiss, minVisible 2500ms
        const val PRIORITY_NOTIFICATION_IMPORTANT = 40
        
        // Notification normal: mini or short big, minVisible 1500-2000ms
        const val PRIORITY_NOTIFICATION_NORMAL = 30
        
        // Media (system/hyperos): routes to SYSTEM_MEDIA
        const val PRIORITY_MEDIA = 20
        
        // Fallback/Progress/Standard: lowest priority
        const val PRIORITY_FALLBACK = 10
        
        // Legacy aliases for compatibility during migration
        @Deprecated("Use PRIORITY_INCOMING_CALL or PRIORITY_ONGOING_CALL", ReplaceWith("PRIORITY_INCOMING_CALL"))
        const val PRIORITY_CALL = PRIORITY_INCOMING_CALL
        @Deprecated("Use PRIORITY_TIMER_RINGING", ReplaceWith("PRIORITY_TIMER_RINGING"))
        const val PRIORITY_TIMER = PRIORITY_TIMER_RINGING
        @Deprecated("Use PRIORITY_NAVIGATION_ACTIVE", ReplaceWith("PRIORITY_NAVIGATION_ACTIVE"))
        const val PRIORITY_NAVIGATION = PRIORITY_NAVIGATION_ACTIVE
        @Deprecated("Use PRIORITY_NOTIFICATION_IMPORTANT", ReplaceWith("PRIORITY_NOTIFICATION_IMPORTANT"))
        const val PRIORITY_NOTIFICATION = PRIORITY_NOTIFICATION_IMPORTANT
        const val PRIORITY_PROGRESS = PRIORITY_FALLBACK
        const val PRIORITY_STANDARD = PRIORITY_FALLBACK
    }
}

/**
 * UI state for the island, derived from ActiveIsland.
 * Used by the host composable for rendering.
 */
data class IslandUiState(
    val featureId: String,
    val notificationKey: String,
    val packageName: String,
    val featureState: Any?,
    val isExpanded: Boolean,
    val isCollapsed: Boolean,
    val isReplying: Boolean,
    val isModal: Boolean,
    val allowPassThrough: Boolean,
    val needsFocus: Boolean
) {
    companion object {
        fun from(island: ActiveIsland): IslandUiState = IslandUiState(
            featureId = island.featureId,
            notificationKey = island.notificationKey,
            packageName = island.packageName,
            featureState = island.state,
            isExpanded = island.isExpanded,
            isCollapsed = island.isCollapsed,
            isReplying = island.isReplying,
            isModal = island.policy.isModal,
            allowPassThrough = island.policy.allowPassThrough,
            needsFocus = island.policy.needsFocus
        )

        val EMPTY = IslandUiState(
            featureId = "",
            notificationKey = "",
            packageName = "",
            featureState = null,
            isExpanded = false,
            isCollapsed = false,
            isReplying = false,
            isModal = false,
            allowPassThrough = true,
            needsFocus = false
        )
    }
}
