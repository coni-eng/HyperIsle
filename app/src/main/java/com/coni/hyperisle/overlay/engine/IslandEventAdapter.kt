package com.coni.hyperisle.overlay.engine

import com.coni.hyperisle.overlay.CallOverlayState
import com.coni.hyperisle.overlay.IosCallOverlayModel
import com.coni.hyperisle.overlay.IosNotificationOverlayModel
import com.coni.hyperisle.overlay.MediaOverlayModel
import com.coni.hyperisle.overlay.NavigationOverlayModel
import com.coni.hyperisle.overlay.OverlayEvent
import com.coni.hyperisle.overlay.TimerOverlayModel



/**
 * Adapter to convert between legacy OverlayEvent and new IslandEvent.
 * This allows gradual migration from the old architecture to the new one.
 */
object IslandEventAdapter {

    /**
     * Convert legacy OverlayEvent to new IslandEvent.
     */
    fun toIslandEvent(event: OverlayEvent): IslandEvent? {
        return when (event) {
            is OverlayEvent.CallEvent -> toCallEvent(event.model)
            is OverlayEvent.NotificationEvent -> toNotificationEvent(event.model)
            is OverlayEvent.MediaEvent -> toMediaEvent(event.model)
            is OverlayEvent.TimerEvent -> toTimerEvent(event.model)
            is OverlayEvent.NavigationEvent -> toNavigationEvent(event.model)
            is OverlayEvent.DownloadEvent -> null
            is OverlayEvent.DismissEvent -> IslandEvent.Dismiss(
                notificationKey = event.notificationKey ?: "",
                reason = "NOTIF_REMOVED"
            )
            is OverlayEvent.DismissAllEvent -> IslandEvent.DismissAll(
                reason = "DISMISS_ALL"
            )
        }
    }

    private fun toCallEvent(model: IosCallOverlayModel): IslandEvent {
        return when (model.state) {
            CallOverlayState.INCOMING -> IslandEvent.IncomingCall(
                notificationKey = model.notificationKey,
                packageName = model.packageName,
                callerName = model.callerName,
                avatarBitmap = model.avatarBitmap,
                contentIntent = model.contentIntent,
                accentColor = model.accentColor,
                title = model.title,
                acceptIntent = model.acceptIntent,
                declineIntent = model.declineIntent
            )
            CallOverlayState.ONGOING -> IslandEvent.OngoingCall(
                notificationKey = model.notificationKey,
                packageName = model.packageName,
                callerName = model.callerName,
                avatarBitmap = model.avatarBitmap,
                contentIntent = model.contentIntent,
                accentColor = model.accentColor,
                durationText = model.durationText,
                hangUpIntent = model.hangUpIntent,
                speakerIntent = model.speakerIntent,
                muteIntent = model.muteIntent
            )
        }
    }

    private fun toNotificationEvent(model: IosNotificationOverlayModel): IslandEvent {
        return IslandEvent.Notification(
            notificationKey = model.notificationKey,
            packageName = model.packageName,
            sender = model.sender,
            message = model.message,
            timeLabel = model.timeLabel,
            avatarBitmap = model.avatarBitmap,
            contentIntent = model.contentIntent,
            replyAction = model.replyAction?.let {
                IslandEvent.ReplyAction(
                    title = it.title,
                    pendingIntent = it.pendingIntent,
                    remoteInputs = it.remoteInputs
                )
            },
            collapseAfterMs = model.collapseAfterMs,
            accentColor = model.accentColor
        )
    }

    private fun toMediaEvent(model: MediaOverlayModel): IslandEvent {
        return IslandEvent.Media(
            notificationKey = model.notificationKey,
            packageName = model.packageName,
            title = model.title,
            subtitle = model.subtitle,
            albumArt = model.albumArt,
            actions = model.actions.map {
                IslandEvent.MediaAction(
                    label = it.label,
                    iconBitmap = it.iconBitmap,
                    actionIntent = it.actionIntent
                )
            },
            contentIntent = model.contentIntent,
            isVideo = model.isVideo,
            accentColor = model.accentColor
        )
    }

    private fun toTimerEvent(model: TimerOverlayModel): IslandEvent {
        return IslandEvent.Timer(
            notificationKey = model.notificationKey,
            packageName = model.packageName,
            label = model.label,
            baseTimeMs = model.baseTimeMs,
            isCountdown = model.isCountdown,
            contentIntent = model.contentIntent,
            accentColor = model.accentColor
        )
    }

    private fun toNavigationEvent(model: NavigationOverlayModel): IslandEvent {
        return IslandEvent.Navigation(
            notificationKey = model.notificationKey,
            packageName = model.packageName,
            instruction = model.instruction,
            distance = model.distance,
            eta = model.eta,
            remainingTime = model.remainingTime,
            totalDistance = model.totalDistance,
            turnDistance = model.turnDistance,
            directionIcon = model.directionIcon,
            appIcon = model.appIcon,
            contentIntent = model.contentIntent,
            isCompact = model.islandSize == com.coni.hyperisle.overlay.NavIslandSize.COMPACT,
            accentColor = model.accentColor
        )
    }
}
