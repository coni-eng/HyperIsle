package com.coni.hyperisle.util

/**
 * Centralized helper for constructing and parsing miui.focus.action_* strings.
 * 
 * Action string format: "miui.focus.action_{type}_{notificationId}"
 * Examples:
 *   - "miui.focus.action_options_12345"
 *   - "miui.focus.action_dismiss_-987654"
 * 
 * This helper reduces fragility by centralizing string manipulation
 * that was previously scattered across IslandActionReceiver and StandardTranslator.
 */
object FocusActionHelper {

    // Action type constants - these match the existing string format
    private const val PREFIX = "miui.focus.action_"
    const val TYPE_OPTIONS = "options"
    const val TYPE_DISMISS = "dismiss"

    // Full action base strings (for receiver registration and startsWith checks)
    const val ACTION_OPTIONS = "${PREFIX}${TYPE_OPTIONS}"
    const val ACTION_DISMISS = "${PREFIX}${TYPE_DISMISS}"

    /**
     * Builds a full action string with notification ID suffix.
     * 
     * @param actionType One of [TYPE_OPTIONS] or [TYPE_DISMISS]
     * @param notificationId The notification ID to encode in the action string
     * @return Full action string, e.g., "miui.focus.action_options_12345"
     */
    fun buildActionString(actionType: String, notificationId: Int): String {
        return "${PREFIX}${actionType}_$notificationId"
    }

    /**
     * Parses the notification ID from a full action string.
     * 
     * Action format: "miui.focus.action_{type}_{notificationId}"
     * The notification ID is the numeric suffix after the last underscore.
     * 
     * @param action The full action string to parse
     * @return The parsed notification ID, or null if parsing fails
     */
    fun parseNotificationId(action: String): Int? {
        // Find the last underscore and parse the number after it
        val lastUnderscoreIndex = action.lastIndexOf('_')
        if (lastUnderscoreIndex == -1) return null

        val idString = action.substring(lastUnderscoreIndex + 1)
        return idString.toIntOrNull()
    }

    /**
     * Checks if the action string is an OPTIONS action.
     */
    fun isOptionsAction(action: String): Boolean = action.startsWith(ACTION_OPTIONS)

    /**
     * Checks if the action string is a DISMISS action.
     */
    fun isDismissAction(action: String): Boolean = action.startsWith(ACTION_DISMISS)
}
