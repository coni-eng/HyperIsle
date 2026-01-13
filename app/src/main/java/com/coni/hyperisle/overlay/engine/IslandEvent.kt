package com.coni.hyperisle.overlay.engine

import android.app.PendingIntent
import android.graphics.Bitmap

/**
 * Sealed interface representing all possible island events.
 * Events are the input to the IslandCoordinator for decision-making.
 */
sealed interface IslandEvent {
    val notificationKey: String
    val packageName: String
    val timestamp: Long
        get() = System.currentTimeMillis()

    // ==================== CALL EVENTS ====================
    
    sealed interface CallEvent : IslandEvent {
        val callerName: String
        val avatarBitmap: Bitmap?
        val contentIntent: PendingIntent?
        val accentColor: String?
    }

    data class IncomingCall(
        override val notificationKey: String,
        override val packageName: String,
        override val callerName: String,
        override val avatarBitmap: Bitmap? = null,
        override val contentIntent: PendingIntent? = null,
        override val accentColor: String? = null,
        val title: String,
        val acceptIntent: PendingIntent? = null,
        val declineIntent: PendingIntent? = null
    ) : CallEvent

    data class OngoingCall(
        override val notificationKey: String,
        override val packageName: String,
        override val callerName: String,
        override val avatarBitmap: Bitmap? = null,
        override val contentIntent: PendingIntent? = null,
        override val accentColor: String? = null,
        val durationText: String = "",
        val hangUpIntent: PendingIntent? = null,
        val speakerIntent: PendingIntent? = null,
        val muteIntent: PendingIntent? = null
    ) : CallEvent

    data class CallEnded(
        override val notificationKey: String,
        override val packageName: String
    ) : IslandEvent

    // ==================== NOTIFICATION EVENTS ====================

    data class Notification(
        override val notificationKey: String,
        override val packageName: String,
        val sender: String,
        val message: String,
        val timeLabel: String,
        val avatarBitmap: Bitmap? = null,
        val contentIntent: PendingIntent? = null,
        val replyAction: ReplyAction? = null,
        val collapseAfterMs: Long? = null,
        val accentColor: String? = null
    ) : IslandEvent

    data class ReplyAction(
        val title: String,
        val pendingIntent: PendingIntent,
        val remoteInputs: Array<android.app.RemoteInput>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ReplyAction) return false
            return title == other.title && pendingIntent == other.pendingIntent
        }
        override fun hashCode(): Int = 31 * title.hashCode() + pendingIntent.hashCode()
    }

    // ==================== NAVIGATION EVENTS ====================

    data class Navigation(
        override val notificationKey: String,
        override val packageName: String,
        val instruction: String,
        val distance: String,
        val eta: String,
        val remainingTime: String = "",
        val totalDistance: String = "",
        val turnDistance: String = "",
        val directionIcon: Bitmap? = null,
        val appIcon: Bitmap? = null,
        val contentIntent: PendingIntent? = null,
        val isCompact: Boolean = true,
        val accentColor: String? = null
    ) : IslandEvent

    // ==================== TIMER EVENTS ====================

    data class Timer(
        override val notificationKey: String,
        override val packageName: String,
        val label: String,
        val baseTimeMs: Long,
        val isCountdown: Boolean,
        val contentIntent: PendingIntent? = null,
        val accentColor: String? = null
    ) : IslandEvent

    // ==================== ALARM EVENTS ====================

    data class Alarm(
        override val notificationKey: String,
        override val packageName: String,
        val label: String,
        val timeLabel: String,
        val dismissIntent: PendingIntent? = null,
        val snoozeIntent: PendingIntent? = null,
        val contentIntent: PendingIntent? = null,
        val accentColor: String? = null
    ) : IslandEvent

    // ==================== MEDIA EVENTS ====================

    data class Media(
        override val notificationKey: String,
        override val packageName: String,
        val title: String,
        val subtitle: String,
        val albumArt: Bitmap? = null,
        val actions: List<MediaAction> = emptyList(),
        val contentIntent: PendingIntent? = null,
        val isVideo: Boolean = false,
        val accentColor: String? = null
    ) : IslandEvent

    data class MediaAction(
        val label: String,
        val iconBitmap: Bitmap? = null,
        val actionIntent: PendingIntent
    )

    // ==================== PROGRESS/STANDARD EVENTS ====================

    data class Progress(
        override val notificationKey: String,
        override val packageName: String,
        val title: String,
        val text: String,
        val progress: Int,
        val maxProgress: Int,
        val contentIntent: PendingIntent? = null,
        val accentColor: String? = null
    ) : IslandEvent

    data class Standard(
        override val notificationKey: String,
        override val packageName: String,
        val title: String,
        val text: String,
        val icon: Bitmap? = null,
        val contentIntent: PendingIntent? = null,
        val accentColor: String? = null
    ) : IslandEvent

    // ==================== CONTROL EVENTS ====================

    data class Dismiss(
        override val notificationKey: String,
        override val packageName: String = "",
        val reason: String = "UNKNOWN"
    ) : IslandEvent

    data class DismissAll(
        override val notificationKey: String = "",
        override val packageName: String = "",
        val reason: String = "UNKNOWN"
    ) : IslandEvent

    /**
     * User-initiated dismiss (swipe, tap X button, etc.)
     */
    data class UserDismiss(
        override val notificationKey: String,
        override val packageName: String = "",
        val reason: String = "USER_ACTION"
    ) : IslandEvent
}
