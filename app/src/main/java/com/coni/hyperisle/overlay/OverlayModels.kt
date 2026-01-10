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
 * Media type for unified media/video island.
 */
enum class MediaType {
    MUSIC,      // Audio-only playback (Spotify, Apple Music, etc.)
    VIDEO,      // Video playback (YouTube, Netflix, video players)
    UNKNOWN     // Cannot determine media type
}

/**
 * Data model for unified media overlay (music AND video).
 * 
 * Both music and video apps use the same island UI with play/pause/seek controls.
 * Video apps are detected by package name patterns or notification metadata.
 */
data class MediaOverlayModel(
    val title: String,
    val subtitle: String,
    val albumArt: Bitmap? = null,
    val actions: List<MediaAction> = emptyList(),
    val contentIntent: PendingIntent? = null,
    val packageName: String,
    val notificationKey: String,
    val mediaType: MediaType = MediaType.UNKNOWN,  // Unified: music or video
    val isVideo: Boolean = false                    // Quick check for video content
) {
    companion object {
        // Known video app packages
        private val VIDEO_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.youtube.tv",
            "com.google.android.youtube.tvkids",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus",
            "com.hbo.hbonow",
            "tv.twitch.android.app",
            "com.zhiliaoapp.musically",  // TikTok
            "com.instagram.android",
            "com.ss.android.ugc.trill",  // TikTok lite
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "com.brouken.player",        // Just Player
            "is.xyz.mpv",                // mpv
            "com.kodi",
            "org.xbmc.kodi",
            "com.plex.client.smarttv",
            "com.plexapp.android"
        )
        
        /**
         * Detect if a package is a video app.
         */
        fun isVideoPackage(packageName: String): Boolean {
            return VIDEO_PACKAGES.contains(packageName) ||
                   packageName.contains("video", ignoreCase = true) ||
                   packageName.contains("player", ignoreCase = true) ||
                   packageName.contains("movie", ignoreCase = true)
        }
        
        /**
         * Determine media type from package name.
         */
        fun detectMediaType(packageName: String): MediaType {
            return if (isVideoPackage(packageName)) MediaType.VIDEO else MediaType.MUSIC
        }
    }
}

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
