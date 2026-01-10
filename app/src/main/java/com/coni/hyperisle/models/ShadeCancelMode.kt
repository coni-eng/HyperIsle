package com.coni.hyperisle.models

/**
 * Per-app notification hiding mode for iOS-like behavior.
 * 
 * This controls what happens to the system notification when HyperIsle shows an island:
 * 
 * ISLAND_ONLY (default): Show island WITHOUT cancelling system notification.
 *   - System notification remains in shade
 *   - System heads-up/pop-up may still appear (OEM dependent)
 *   - User must disable pop-ups in system settings for true iOS-like behavior
 *   - Safest option - no notification data loss
 * 
 * HIDE_POPUP_KEEP_SHADE: Attempt to suppress pop-up but keep in notification shade.
 *   - Uses snoozeNotification() where available (API 26+)
 *   - Falls back to ISLAND_ONLY behavior on unsupported devices
 *   - Notification stays in shade for later viewing
 * 
 * FULLY_HIDE: Cancel system notification completely (removes from shade).
 *   - Uses cancelNotification() to remove from shade
 *   - True iOS-like behavior where island replaces system notification
 *   - Risk: Notification data may be lost if island is missed
 *   - Only works for clearable notifications (not ongoing/foreground service)
 * 
 * AGGRESSIVE: Like FULLY_HIDE but attempts to cancel even harder-to-cancel notifications.
 *   - May cause issues with some apps
 *   - Use with caution
 * 
 * Legacy mapping:
 * - Old SAFE → ISLAND_ONLY (safest default)
 * - Old AGGRESSIVE → AGGRESSIVE (unchanged)
 */
enum class ShadeCancelMode {
    ISLAND_ONLY,        // Show island, don't touch system notification
    HIDE_POPUP_KEEP_SHADE, // Snooze/suppress popup, keep in shade
    FULLY_HIDE,         // Cancel notification from shade (standard clearable only)
    AGGRESSIVE,         // Cancel even harder-to-cancel notifications
    
    // Legacy alias for migration
    @Deprecated("Use ISLAND_ONLY instead", ReplaceWith("ISLAND_ONLY"))
    SAFE;
    
    companion object {
        /**
         * Migrate legacy mode values to new enum.
         */
        fun fromLegacyValue(value: String): ShadeCancelMode {
            return when (value.uppercase()) {
                "SAFE" -> ISLAND_ONLY
                "AGGRESSIVE" -> AGGRESSIVE
                else -> try {
                    valueOf(value.uppercase())
                } catch (e: Exception) {
                    ISLAND_ONLY
                }
            }
        }
        
        /**
         * Whether this mode should attempt to cancel the notification.
         */
        fun shouldCancel(mode: ShadeCancelMode): Boolean {
            return mode == FULLY_HIDE || mode == AGGRESSIVE
        }
        
        /**
         * Whether this mode should attempt to snooze (suppress popup).
         */
        fun shouldSnooze(mode: ShadeCancelMode): Boolean {
            return mode == HIDE_POPUP_KEEP_SHADE
        }
    }
}
