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
    val notificationKey: String,
    val accentColor: String? = null,
    val interactive: Boolean = true,
    // BUG#1 FIX: Capability flags - button disabled when false
    val canHangup: Boolean = true,
    val canSpeaker: Boolean = true,
    val canMute: Boolean = true,
    // BUG#3 FIX: Audio state flags for UI feedback
    val isSpeakerOn: Boolean = false,
    val isMuted: Boolean = false,
    // BUG#2 FIX: Unique call key for proper lifecycle management
    val callKey: String = notificationKey
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
    val isVideo: Boolean = false,                   // Quick check for video content
    val accentColor: String? = null
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
    val notificationKey: String,
    val accentColor: String? = null
)

/**
 * Download overlay state for anchor rendering.
 */
enum class DownloadStatus {
    ACTIVE,
    COMPLETED,
    REMOVED
}

/**
 * Data model for download progress overlay (anchor-only).
 */
data class DownloadOverlayModel(
    val stableKey: String,
    val packageName: String,
    val progress: Int,
    val maxProgress: Int,
    val indeterminate: Boolean,
    val title: String = "",
    val text: String = "",
    val subText: String = "",
    val status: DownloadStatus = DownloadStatus.ACTIVE
)

/**
 * Navigation island size mode.
 */
enum class NavIslandSize {
    COMPACT,  // Small island with 2 info slots (left/right of camera)
    EXPANDED  // Large island with 4 info slots
}

/**
 * Navigation info slot content type.
 */
enum class NavSlotContent {
    DIRECTION,      // Turn direction icon + text (e.g., "Turn right")
    DISTANCE,       // Distance to next turn (e.g., "200m")
    TURN_DISTANCE,  // Distance to turn point
    ETA,            // Estimated time of arrival
    REMAINING_TIME, // Remaining travel time
    TOTAL_DISTANCE, // Total route distance
    TOTAL_TIME,     // Total estimated time
    NONE            // Empty slot
}

/**
 * Data model for iOS-style navigation overlay.
 */
data class NavigationOverlayModel(
    val instruction: String,           // "Turn right onto Main St"
    val distance: String,              // "200m"
    val eta: String,                   // "10:30"
    val remainingTime: String = "",    // "15 min"
    val totalDistance: String = "",    // "5.2 km"
    val turnDistance: String = "",     // "In 200m"
    val directionIcon: Bitmap? = null, // Turn arrow icon
    val appIcon: Bitmap? = null,       // Navigation app icon
    val contentIntent: PendingIntent? = null,
    val packageName: String,
    val notificationKey: String,
    val islandSize: NavIslandSize = NavIslandSize.COMPACT,
    val accentColor: String? = null,
    val actionCount: Int = 0
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
 * Media content type for notification messages (sticker, photo, video, GIF, etc.)
 */
enum class NotificationMediaType {
    NONE,       // Regular text message
    PHOTO,      // 📷 Photo
    VIDEO,      // 🎬 Video
    GIF,        // GIF animation
    STICKER,    // Sticker
    VOICE,      // 🎤 Voice message
    DOCUMENT,   // 📄 Document
    LOCATION,   // 📍 Location
    CONTACT     // 👤 Contact
}

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
    val replyAction: IosNotificationReplyAction? = null,
    val accentColor: String? = null,
    val mediaType: NotificationMediaType = NotificationMediaType.NONE,
    val mediaBitmap: Bitmap? = null  // Preview image for photo/video/sticker
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
     * Event to show a navigation pill overlay.
     */
    data class NavigationEvent(val model: NavigationOverlayModel) : OverlayEvent()

    /**
     * Event to show a download progress state on the anchor.
     */
    data class DownloadEvent(val model: DownloadOverlayModel) : OverlayEvent()

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
