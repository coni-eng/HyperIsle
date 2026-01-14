package com.coni.hyperisle.models

/**
 * Controls when the anchor island is visible.
 * 
 * ALWAYS: Anchor visible on lock screen AND normal screen (persistent)
 * UNLOCKED_ONLY: Anchor visible only when device is unlocked (normal screen)
 * LOCK_SCREEN_ONLY: Anchor visible only on lock screen
 * TRIGGERED_ONLY: Anchor visible only when a notification/call/nav triggers it
 */
enum class AnchorVisibilityMode {
    /**
     * Anchor always visible on both lock screen and normal screen.
     * Best for users who want persistent Dynamic Island experience.
     */
    ALWAYS,
    
    /**
     * Anchor visible only when device is unlocked.
     * Hides on lock screen for cleaner look.
     */
    UNLOCKED_ONLY,
    
    /**
     * Anchor visible only on lock screen.
     */
    LOCK_SCREEN_ONLY,
    
    /**
     * Anchor only appears when triggered by notification/call/navigation.
     * Most battery-efficient, least intrusive.
     */
    TRIGGERED_ONLY;
    
    companion object {
        /**
         * Get display name for UI.
         */
        fun getDisplayName(mode: AnchorVisibilityMode): String {
            return when (mode) {
                ALWAYS -> "Kilit Ekranı + Normal Ekran"
                UNLOCKED_ONLY -> "Sadece Normal Ekran"
                LOCK_SCREEN_ONLY -> "Sadece Kilit Ekranı"
                TRIGGERED_ONLY -> "Sadece Tetiklendiğinde"
            }
        }
        
        /**
         * Get description for UI.
         */
        fun getDescription(mode: AnchorVisibilityMode): String {
            return when (mode) {
                ALWAYS -> "Ada her zaman görünür (kilit ekranı dahil)"
                UNLOCKED_ONLY -> "Ada sadece cihaz açıkken görünür"
                LOCK_SCREEN_ONLY -> "Ada sadece kilit ekranında görünür"
                TRIGGERED_ONLY -> "Ada sadece bildirim/arama geldiğinde görünür"
            }
        }
        
        /**
         * Parse from string with fallback.
         */
        fun fromString(value: String?): AnchorVisibilityMode {
            return try {
                if (value != null) valueOf(value) else TRIGGERED_ONLY
            } catch (e: Exception) {
                TRIGGERED_ONLY
            }
        }
    }
}
