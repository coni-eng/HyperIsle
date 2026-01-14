package com.coni.hyperisle.models



/**
 * Per-app notification hiding mode for iOS-like behavior.
 * 
 * This controls what happens to the system notification when HyperIsle shows an island:
 * 
 * STASH (default for WhatsApp/Telegram): Show island, NEVER cancel system notification.
 *   - System notification remains in shade AND status bar
 *   - System heads-up/pop-up may still appear (OEM dependent)
 *   - Guaranteed stash behavior - notification always accessible
 *   - Best for messaging apps where users want to reply later
 * 
 * ISLAND_ONLY: Show island WITHOUT cancelling system notification.
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
 *   - WARNING: On MIUI/HyperOS, may cause "mini island" to appear after HyperIsle island
 * 
 * Legacy mapping:
 * - Old SAFE → ISLAND_ONLY (safest default)
 * - Old AGGRESSIVE → AGGRESSIVE (unchanged)
 */
enum class ShadeCancelMode {
    STASH,              // Show island, NEVER cancel/snooze - keep in shade + status bar (best for messaging)
    ISLAND_ONLY,        // Show island, don't touch system notification
    HIDE_POPUP_KEEP_SHADE, // Snooze/suppress popup, keep in shade
    FULLY_HIDE,         // Cancel notification from shade (standard clearable only)
    AGGRESSIVE,         // Cancel even harder-to-cancel notifications
    
    // Legacy alias for migration
    @Deprecated("Use ISLAND_ONLY instead", ReplaceWith("ISLAND_ONLY"))
    SAFE;
    
    companion object {
        /**
         * Messaging app packages that default to STASH mode.
         * These apps should never have their notifications snoozed/cancelled.
         */
        val MESSAGING_PACKAGES = setOf(
            // WhatsApp
            "com.whatsapp",
            "com.whatsapp.w4b",
            // Telegram variants
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
            "org.telegram.plus",
            "nekogram.messenger",
            "org.telegram.BifToGram",
            // Facebook/Meta
            "com.facebook.orca",
            "com.facebook.mlite",
            "com.instagram.android",
            // Discord & Slack
            "com.discord",
            "com.Slack",
            // Viber & Line
            "com.viber.voip",
            "jp.naver.line.android",
            // Google Messages
            "com.google.android.apps.messaging",
            // Samsung Messages
            "com.samsung.android.messaging",
            // Signal
            "org.thoughtcrime.securesms",
            // Snapchat
            "com.snapchat.android",
            // WeChat
            "com.tencent.mm",
            // Skype
            "com.skype.raider",
            // Microsoft Teams
            "com.microsoft.teams",
            // Kakao
            "com.kakao.talk",
            // Threema
            "ch.threema.app",
            // Wire
            "com.wire",
            // Element/Matrix
            "im.vector.app"
        )
        
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
         * Get default mode for a package.
         * Messaging apps default to STASH, others to ISLAND_ONLY.
         */
        fun getDefaultForPackage(packageName: String): ShadeCancelMode {
            return if (MESSAGING_PACKAGES.contains(packageName)) {
                STASH
            } else {
                ISLAND_ONLY
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
        
        /**
         * Whether this mode is a "stash" mode (never cancel or snooze).
         */
        fun isStashMode(mode: ShadeCancelMode): Boolean {
            return mode == STASH
        }
    }
}
