package com.coni.hyperisle.overlay

import android.app.PendingIntent
import android.graphics.Bitmap

/**
 * Data model for iOS-style call overlay.
 */
data class IosCallOverlayModel(
    val title: String,
    val callerName: String,
    val avatarBitmap: Bitmap? = null,
    val acceptIntent: PendingIntent? = null,
    val declineIntent: PendingIntent? = null,
    val packageName: String,
    val notificationKey: String
)

/**
 * Data model for iOS-style notification overlay.
 */
data class IosNotificationOverlayModel(
    val sender: String,
    val timeLabel: String,
    val message: String,
    val avatarBitmap: Bitmap? = null,
    val contentIntent: PendingIntent? = null,
    val packageName: String,
    val notificationKey: String
)

/**
 * Sealed class representing overlay events that can be emitted to the overlay system.
 */
sealed class OverlayEvent {
    /**
     * Event to show an incoming call pill overlay.
     */
    data class CallEvent(val model: IosCallOverlayModel) : OverlayEvent()

    /**
     * Event to show a notification pill overlay.
     */
    data class NotificationEvent(val model: IosNotificationOverlayModel) : OverlayEvent()

    /**
     * Event to dismiss the current overlay.
     */
    data class DismissEvent(val notificationKey: String? = null) : OverlayEvent()

    /**
     * Event to dismiss all overlays.
     */
    object DismissAllEvent : OverlayEvent()
}
