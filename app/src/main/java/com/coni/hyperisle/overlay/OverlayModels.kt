package com.coni.hyperisle.overlay

import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.Bitmap

enum class CallOverlayState {
    INCOMING,
    ONGOING
}

/**
 * Data model for iOS-style call overlay.
 */
data class IosCallOverlayModel(
    val title: String,
    val callerName: String,
    val avatarBitmap: Bitmap? = null,
    val contentIntent: PendingIntent? = null,
    val acceptIntent: PendingIntent? = null,
    val declineIntent: PendingIntent? = null,
    val hangUpIntent: PendingIntent? = null,
    val speakerIntent: PendingIntent? = null,
    val muteIntent: PendingIntent? = null,
    val durationText: String = "",
    val state: CallOverlayState = CallOverlayState.INCOMING,
    val packageName: String,
    val notificationKey: String
)

/**
 * Action model for media overlay controls.
 */
data class MediaAction(
    val label: String,
    val iconBitmap: Bitmap? = null,
    val actionIntent: PendingIntent
)

/**
 * Data model for media overlay.
 */
data class MediaOverlayModel(
    val title: String,
    val subtitle: String,
    val albumArt: Bitmap? = null,
    val actions: List<MediaAction> = emptyList(),
    val contentIntent: PendingIntent? = null,
    val packageName: String,
    val notificationKey: String
)

/**
 * Data model for timer/chronometer overlay.
 */
data class TimerOverlayModel(
    val label: String,
    val baseTimeMs: Long,
    val isCountdown: Boolean,
    val contentIntent: PendingIntent? = null,
    val packageName: String,
    val notificationKey: String
)

/**
 * Inline reply action info for notification overlay.
 */
data class IosNotificationReplyAction(
    val title: String,
    val pendingIntent: PendingIntent,
    val remoteInputs: Array<RemoteInput>
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
    val notificationKey: String,
    val collapseAfterMs: Long? = null,
    val replyAction: IosNotificationReplyAction? = null
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
     * Event to show a media pill overlay.
     */
    data class MediaEvent(val model: MediaOverlayModel) : OverlayEvent()

    /**
     * Event to show a timer/chronometer overlay.
     */
    data class TimerEvent(val model: TimerOverlayModel) : OverlayEvent()

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
