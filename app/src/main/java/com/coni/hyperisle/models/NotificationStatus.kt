package com.coni.hyperisle.models

/**
 * Self-reported notification status for apps in the shade cancel list.
 * 
 * Since Android doesn't provide a public API to check if another app's notifications
 * are enabled, we ask users to report the status after they visit system settings.
 * 
 * @since v1.0.0
 */
enum class NotificationStatus {
    /**
     * User confirmed they disabled notifications for this app in system settings.
     * Island rendering is safe - no duplicate notifications will appear.
     */
    DISABLED,

    /**
     * User confirmed notifications are still enabled for this app.
     * May result in duplicate islands if MIUI/HyperOS forces system notifications.
     */
    ENABLED,

    /**
     * Status not yet reported by user (default state).
     */
    UNKNOWN
}
