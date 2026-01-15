package com.coni.hyperisle.overlay.anchor



/**
 * Island mode for anchor-based display system.
 * 
 * The anchor pill is always visible around the camera cutout.
 * Different modes display different content in the left/right slots
 * while keeping the center (cutout gap) empty.
 */
enum class IslandMode {
    /**
     * Anchor idle - always visible, minimal display.
     * Shows clock/battery or other user-selected info in slots.
     */
    ANCHOR_IDLE,

    /**
     * Active call mode - anchor displays call info.
     * LeftSlot: wave bar animation or phone icon
     * RightSlot: call duration (mm:ss)
     */
    CALL_ACTIVE,

    /**
     * Navigation active mode - anchor displays nav info.
     * LeftSlot: user-selected info (e.g., "250m sonra sağ")
     * RightSlot: user-selected info (e.g., ETA)
     */
    NAV_ACTIVE,

    /**
     * Notification expanded mode - full notification display.
     * Anchor expands to show notification content.
     * After cooldown/dismiss, shrinks back to previous mode.
     */
    NOTIF_EXPANDED,

    /**
     * Navigation expanded mode - larger navigation display.
     * Triggered by long press on navigation anchor.
     */
    NAV_EXPANDED;

    /**
     * Check if this mode is an anchor mode (not expanded).
     */
    fun isAnchor(): Boolean = this != NOTIF_EXPANDED && this != NAV_EXPANDED

    /**
     * Check if this mode should show content in slots.
     */
    fun hasSlotContent(): Boolean = this != NOTIF_EXPANDED && this != NAV_EXPANDED

    /**
     * Get the fallback mode when transitioning from NOTIF_EXPANDED.
     * Priority: CALL_ACTIVE > NAV_ACTIVE > ANCHOR_IDLE
     */
    companion object {
        fun getFallbackMode(hasActiveCall: Boolean, hasActiveNav: Boolean): IslandMode {
            return when {
                hasActiveCall -> CALL_ACTIVE
                hasActiveNav -> NAV_ACTIVE
                else -> ANCHOR_IDLE
            }
        }
    }
}
