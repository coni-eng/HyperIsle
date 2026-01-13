package com.coni.hyperisle.overlay.engine

/**
 * Policy configuration for an island.
 * Defines behavior, timing, and interaction rules.
 * 
 * Features return policies based on their current state.
 * The coordinator uses policies for timing and preemption decisions.
 */
data class IslandPolicy(
    /**
     * Minimum time the island must be visible (ms).
     * Prevents flickering from rapid notification updates.
     */
    val minVisibleMs: Long = 2500L,

    /**
     * Time before auto-collapse to mini mode (ms).
     * Null = never collapse, 0 = immediately collapsed.
     */
    val collapseAfterMs: Long? = 4000L,

    /**
     * Time before auto-dismiss after collapse (ms).
     * Null = never auto-dismiss after collapse.
     */
    val dismissAfterCollapseMs: Long? = 3000L,

    /**
     * Whether this island is modal (blocks other islands).
     * Modal islands capture all touch events.
     */
    val isModal: Boolean = false,

    /**
     * Allow touch events to pass through to underlying apps.
     * True for compact non-modal islands.
     */
    val allowPassThrough: Boolean = true,

    /**
     * Allow expanding to larger view on long-press.
     */
    val allowExpand: Boolean = true,

    /**
     * Allow inline reply (notifications with RemoteInput).
     */
    val allowReply: Boolean = false,

    /**
     * Show on keyguard (lock screen).
     */
    val showOnKeyguard: Boolean = false,

    /**
     * Sticky island (does not auto-dismiss).
     * Used for ongoing calls, timers, navigation.
     */
    val sticky: Boolean = false,

    /**
     * Allow user swipe-to-dismiss.
     */
    val dismissible: Boolean = true,

    /**
     * Suppress when source app is foreground.
     * Prevents duplicate UI when user is already in the app.
     */
    val suppressWhenAppForeground: Boolean = true,

    /**
     * Suppress when specific packages are foreground (e.g., dialer for calls).
     */
    val suppressOnForegroundPackages: Set<String> = emptySet(),

    /**
     * Window needs to be focusable (for keyboard input in reply mode).
     */
    val needsFocus: Boolean = false
) {
    companion object {
        // Pre-defined policies for common scenarios
        
        val INCOMING_CALL = IslandPolicy(
            minVisibleMs = 0L,
            collapseAfterMs = null,
            dismissAfterCollapseMs = null,
            isModal = false,
            allowPassThrough = false, // Need to capture accept/decline taps
            allowExpand = false,
            showOnKeyguard = true,
            sticky = true,
            dismissible = true,
            suppressOnForegroundPackages = setOf(
                "com.google.android.dialer",
                "com.android.incallui",
                "com.samsung.android.incallui"
            )
        )

        val ONGOING_CALL = IslandPolicy(
            minVisibleMs = 0L,
            collapseAfterMs = null,
            dismissAfterCollapseMs = null,
            isModal = false,
            allowPassThrough = false, // Tap to open in-call UI
            allowExpand = true,
            showOnKeyguard = true,
            sticky = true,
            dismissible = true,
            suppressOnForegroundPackages = setOf(
                "com.google.android.dialer",
                "com.android.incallui",
                "com.samsung.android.incallui"
            )
        )

        val NOTIFICATION = IslandPolicy(
            minVisibleMs = 2500L,
            collapseAfterMs = 4000L,
            dismissAfterCollapseMs = 3000L,
            isModal = false,
            allowPassThrough = true,
            allowExpand = true,
            allowReply = true,
            showOnKeyguard = false,
            sticky = false,
            dismissible = true
        )

        val NOTIFICATION_REPLY = IslandPolicy(
            minVisibleMs = 0L,
            collapseAfterMs = null,
            dismissAfterCollapseMs = null,
            isModal = true,
            allowPassThrough = false,
            allowExpand = false,
            allowReply = true,
            showOnKeyguard = false,
            sticky = false,
            dismissible = true,
            needsFocus = true
        )

        val NAVIGATION = IslandPolicy(
            minVisibleMs = 2500L,
            collapseAfterMs = null,
            dismissAfterCollapseMs = null,
            isModal = false,
            allowPassThrough = true,
            allowExpand = false,
            showOnKeyguard = false,
            sticky = true,
            dismissible = true
        )

        val TIMER = IslandPolicy(
            minVisibleMs = 2500L,
            collapseAfterMs = null,
            dismissAfterCollapseMs = null,
            isModal = false,
            allowPassThrough = true,
            allowExpand = false,
            showOnKeyguard = false,
            sticky = true,
            dismissible = true
        )

        val ALARM = IslandPolicy(
            minVisibleMs = 0L,
            collapseAfterMs = null,
            dismissAfterCollapseMs = null,
            isModal = false,
            allowPassThrough = false, // Need to capture dismiss/snooze taps
            allowExpand = false,
            showOnKeyguard = true,
            sticky = true,
            dismissible = true,
            suppressOnForegroundPackages = setOf(
                "com.google.android.deskclock",
                "com.android.deskclock",
                "com.sec.android.app.clockpackage"
            )
        )

        val MEDIA = IslandPolicy(
            minVisibleMs = 2500L,
            collapseAfterMs = null,
            dismissAfterCollapseMs = null,
            isModal = false,
            allowPassThrough = true,
            allowExpand = true,
            showOnKeyguard = false,
            sticky = true,
            dismissible = true
        )

        val STANDARD = IslandPolicy(
            minVisibleMs = 2500L,
            collapseAfterMs = 4000L,
            dismissAfterCollapseMs = 3000L,
            isModal = false,
            allowPassThrough = true,
            allowExpand = false,
            showOnKeyguard = false,
            sticky = false,
            dismissible = true
        )
    }
}
