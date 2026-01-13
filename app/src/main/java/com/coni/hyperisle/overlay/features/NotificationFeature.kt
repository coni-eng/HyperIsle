package com.coni.hyperisle.overlay.features

import android.app.PendingIntent
import android.app.RemoteInput
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.overlay.engine.ActiveIsland
import com.coni.hyperisle.overlay.engine.IslandActions
import com.coni.hyperisle.overlay.engine.IslandEvent
import com.coni.hyperisle.overlay.engine.IslandPolicy
import com.coni.hyperisle.overlay.engine.IslandRoute
import com.coni.hyperisle.ui.components.MiniNotificationPill
import com.coni.hyperisle.ui.components.NotificationPill
import com.coni.hyperisle.ui.components.NotificationReplyPill

/**
 * Feature for handling notification islands.
 * Supports big/mini modes and inline reply.
 */
class NotificationFeature : IslandFeature {
    override val id: String = "notification"

    override fun canHandle(event: IslandEvent): Boolean {
        return event is IslandEvent.Notification
    }

    override fun reduce(prev: Any?, event: IslandEvent, nowMs: Long): Any? {
        val notifEvent = event as? IslandEvent.Notification ?: return prev
        
        return NotificationState(
            notificationKey = notifEvent.notificationKey,
            packageName = notifEvent.packageName,
            sender = notifEvent.sender,
            message = notifEvent.message,
            timeLabel = notifEvent.timeLabel,
            avatarBitmap = notifEvent.avatarBitmap,
            contentIntent = notifEvent.contentIntent,
            replyAction = notifEvent.replyAction?.let {
                NotificationState.ReplyActionData(
                    title = it.title,
                    pendingIntent = it.pendingIntent,
                    remoteInputs = it.remoteInputs
                )
            },
            collapseAfterMs = notifEvent.collapseAfterMs,
            accentColor = notifEvent.accentColor
        )
    }

    override fun priority(state: Any?): Int {
        val notifState = state as? NotificationState
        // Notifications with reply action are considered "important" (heads-up style)
        return if (notifState?.replyAction != null) {
            ActiveIsland.PRIORITY_NOTIFICATION_IMPORTANT
        } else {
            ActiveIsland.PRIORITY_NOTIFICATION_NORMAL
        }
    }

    override fun route(state: Any?): IslandRoute = IslandRoute.APP_OVERLAY

    override fun policy(state: Any?): IslandPolicy {
        val notifState = state as? NotificationState
        val hasReply = notifState?.replyAction != null
        
        return IslandPolicy.NOTIFICATION.copy(
            allowReply = hasReply,
            collapseAfterMs = notifState?.collapseAfterMs ?: 4000L
        )
    }

    @Composable
    override fun Render(
        state: Any?,
        uiState: FeatureUiState,
        actions: IslandActions
    ) {
        val notifState = state as? NotificationState ?: return
        val rid = uiState.debugRid
        
        // Local reply text state
        var replyText by remember(notifState.notificationKey) { mutableStateOf("") }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            when {
                uiState.isCollapsed -> {
                    MiniNotificationPill(
                        sender = notifState.sender,
                        avatarBitmap = notifState.avatarBitmap,
                        onDismiss = {
                            if (BuildConfig.DEBUG) {
                                Log.d("HyperIsleIsland", "RID=$rid EVT=BTN_RED_X_CLICK reason=OVERLAY")
                            }
                            actions.dismiss("BTN_RED_X")
                        },
                        debugRid = rid
                    )
                }
                uiState.isReplying && notifState.replyAction != null -> {
                    NotificationReplyPill(
                        sender = notifState.sender,
                        message = notifState.message,
                        avatarBitmap = notifState.avatarBitmap,
                        replyText = replyText,
                        onReplyChange = { replyText = it },
                        onSend = {
                            val trimmed = replyText.trim()
                            if (trimmed.isEmpty()) {
                                if (BuildConfig.DEBUG) {
                                    Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_FAIL reason=EMPTY_INPUT")
                                }
                                return@NotificationReplyPill
                            }
                            if (BuildConfig.DEBUG) {
                                Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_TRY")
                            }
                            val result = actions.sendInlineReply(
                                notifState.replyAction.pendingIntent,
                                notifState.replyAction.remoteInputs,
                                trimmed
                            )
                            if (result) {
                                if (BuildConfig.DEBUG) {
                                    Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_OK")
                                }
                                replyText = ""
                                actions.exitReplyMode()
                                actions.dismiss("REPLY_SENT")
                            }
                        },
                        sendLabel = "Send", // TODO: Use string resource
                        debugRid = rid
                    )
                }
                else -> {
                    NotificationPill(
                        sender = notifState.sender,
                        timeLabel = notifState.timeLabel,
                        message = notifState.message,
                        avatarBitmap = notifState.avatarBitmap,
                        accentColor = notifState.accentColor,
                        onDismiss = {
                            if (BuildConfig.DEBUG) {
                                Log.d("HyperIsleIsland", "RID=$rid EVT=BTN_RED_X_CLICK reason=OVERLAY")
                            }
                            actions.dismiss("BTN_RED_X")
                        },
                        debugRid = rid
                    )
                }
            }
        }
    }
}

/**
 * Notification state.
 */
data class NotificationState(
    val notificationKey: String,
    val packageName: String,
    val sender: String,
    val message: String,
    val timeLabel: String,
    val avatarBitmap: Bitmap? = null,
    val contentIntent: PendingIntent? = null,
    val replyAction: ReplyActionData? = null,
    val collapseAfterMs: Long? = null,
    val accentColor: String? = null
) {
    data class ReplyActionData(
        val title: String,
        val pendingIntent: PendingIntent,
        val remoteInputs: Array<RemoteInput>
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ReplyActionData) return false
            return title == other.title && pendingIntent == other.pendingIntent
        }
        override fun hashCode(): Int = 31 * title.hashCode() + pendingIntent.hashCode()
    }
}
