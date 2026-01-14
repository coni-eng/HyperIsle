package com.coni.hyperisle.util

import android.service.notification.StatusBarNotification
import com.coni.hyperisle.models.IslandStyle
import com.coni.hyperisle.models.NotificationType
import com.coni.hyperisle.util.HiLog
import com.coni.hyperisle.util.getStringCompatOrEmpty



/**
 * Island UI Style Contract.
 * 
 * Enforces that legacy notification action-row styles are never rendered.
 * All style decisions are deterministic and logged via HiLog.
 */
object IslandStyleContract {

    /**
     * Result of style resolution.
     * @param style The final style to render (never LEGACY_BLOCKED)
     * @param wasBlocked True if a legacy style was detected and blocked
     * @param reason Human-readable reason for the style selection
     */
    data class StyleResult(
        val style: IslandStyle,
        val wasBlocked: Boolean,
        val reason: String
    )

    /**
     * Resolves the island style for a notification.
     * 
     * This method:
     * 1. Detects if the notification would render as legacy action-row style
     * 2. Blocks legacy styles and falls back to MODERN_PILL
     * 3. Logs the decision deterministically
     * 
     * @param sbn The status bar notification
     * @param type The detected notification type
     * @param actionCount Number of actions in the notification
     * @return StyleResult with the resolved style and metadata
     */
    fun resolveStyle(
        sbn: StatusBarNotification,
        type: NotificationType,
        actionCount: Int
    ): StyleResult {
        val detectedStyle = detectStyle(sbn, type, actionCount)
        
        return if (detectedStyle == IslandStyle.LEGACY_BLOCKED) {
            // Block legacy style, fallback to MODERN_PILL
            val result = StyleResult(
                style = IslandStyle.MODERN_PILL,
                wasBlocked = true,
                reason = "legacy_action_row_blocked"
            )
            
            // Log blocked style
            HiLog.i(HiLog.TAG_STYLE, "ISLAND_STYLE_BLOCKED", mapOf(
                "event" to "ISLAND_STYLE_BLOCKED",
                "detected" to "LEGACY",
                "fallback" to "MODERN_PILL",
                "pkg" to sbn.packageName,
                "notifKeyHash" to HiLog.hashKey(sbn.key),
                "actionCount" to actionCount,
                "originalType" to type.name
            ))
            
            result
        } else {
            StyleResult(
                style = detectedStyle,
                wasBlocked = false,
                reason = getStyleReason(detectedStyle, type)
            )
        }
    }

    /**
     * Logs the final style selection. Call this when an island is about to be shown.
     */
    fun logStyleSelected(
        sbn: StatusBarNotification,
        result: StyleResult
    ) {
        HiLog.i(HiLog.TAG_STYLE, "ISLAND_STYLE_SELECTED", mapOf(
            "event" to "ISLAND_STYLE_SELECTED",
            "style" to result.style.name,
            "reason" to result.reason,
            "pkg" to sbn.packageName,
            "notifKeyHash" to HiLog.hashKey(sbn.key),
            "wasBlocked" to result.wasBlocked
        ))
    }

    /**
     * Detects what style the notification would naturally render as.
     * Returns LEGACY_BLOCKED if legacy action-row style is detected.
     */
    private fun detectStyle(
        sbn: StatusBarNotification,
        type: NotificationType,
        actionCount: Int
    ): IslandStyle {
        // Call notifications use MODERN_CALL style
        if (type == NotificationType.CALL) {
            return IslandStyle.MODERN_CALL
        }

        // Detect legacy action-row style indicators:
        // 1. More than 3 actions (would overflow into expanded row)
        // 2. Notification has BigTextStyle or InboxStyle with many actions
        // 3. Actions without icons (text-only row style)
        if (isLegacyActionRowStyle(sbn, actionCount)) {
            return IslandStyle.LEGACY_BLOCKED
        }

        // All other types use MODERN_PILL
        return IslandStyle.MODERN_PILL
    }

    /**
     * Checks if the notification would render as legacy Android-like action row.
     * 
     * Legacy indicators:
     * - More than 3 visible actions (causes expanded action row)
     * - Actions configured for text-only display without icons
     * - BigTextStyle/InboxStyle with action overflow
     */
    private fun isLegacyActionRowStyle(
        sbn: StatusBarNotification,
        actionCount: Int
    ): Boolean {
        val notification = sbn.notification
        val actions = notification.actions ?: return false

        // Check 1: Too many actions would cause expanded row layout
        if (actionCount > 3) {
            return true
        }

        // Check 2: Detect text-only actions (no icons) which render as legacy row
        val textOnlyActions = actions.count { action ->
            action.getIcon() == null && !action.title.isNullOrEmpty()
        }
        if (textOnlyActions > 1) {
            return true
        }

        // Check 3: Check for legacy templates that force action row
        val extras = notification.extras
        val template = extras.getStringCompatOrEmpty(android.app.Notification.EXTRA_TEMPLATE)
        
        // BigTextStyle and InboxStyle with multiple actions tend to use legacy row
        val isLegacyTemplate = template.contains("BigTextStyle") || 
                               template.contains("InboxStyle")
        if (isLegacyTemplate && actionCount > 2) {
            return true
        }

        return false
    }

    private fun getStyleReason(style: IslandStyle, type: NotificationType): String {
        return when (style) {
            IslandStyle.MODERN_CALL -> "call_notification"
            IslandStyle.MODERN_PILL -> when (type) {
                NotificationType.STANDARD -> "standard_notification"
                NotificationType.PROGRESS -> "progress_notification"
                NotificationType.TIMER -> "timer_notification"
                NotificationType.NAVIGATION -> "navigation_notification"
                NotificationType.MEDIA -> "media_notification"
                NotificationType.CALL -> "call_fallback"
            }
            IslandStyle.LEGACY_BLOCKED -> "legacy_blocked" // Should never reach here
        }
    }
}
