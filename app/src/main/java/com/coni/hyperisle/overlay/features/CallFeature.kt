package com.coni.hyperisle.overlay.features

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.overlay.engine.ActiveIsland
import com.coni.hyperisle.overlay.engine.IslandActions
import com.coni.hyperisle.overlay.engine.IslandEvent
import com.coni.hyperisle.overlay.engine.IslandPolicy
import com.coni.hyperisle.overlay.engine.IslandRoute
import com.coni.hyperisle.receiver.CallActionReceiver
import com.coni.hyperisle.ui.components.ActiveCallCompactPill
import com.coni.hyperisle.ui.components.ActiveCallExpandedPill
import com.coni.hyperisle.ui.components.IncomingCallPill
import com.coni.hyperisle.util.HiLog



/**
 * Feature for handling phone call islands.
 * Supports incoming, ongoing, and ended call states.
 */
class CallFeature : IslandFeature {
    override val id: String = "call"

    override fun canHandle(event: IslandEvent): Boolean {
        return event is IslandEvent.CallEvent || event is IslandEvent.CallEnded
    }

    override fun reduce(prev: Any?, event: IslandEvent, nowMs: Long): Any? {
        return when (event) {
            is IslandEvent.IncomingCall -> CallState.Incoming(
                notificationKey = event.notificationKey,
                packageName = event.packageName,
                title = event.title,
                callerName = event.callerName,
                avatarBitmap = event.avatarBitmap,
                contentIntent = event.contentIntent,
                acceptIntent = event.acceptIntent,
                declineIntent = event.declineIntent,
                accentColor = event.accentColor
            )
            is IslandEvent.OngoingCall -> {
                val prevOngoing = prev as? CallState.Ongoing
                CallState.Ongoing(
                    notificationKey = event.notificationKey,
                    packageName = event.packageName,
                    callerName = event.callerName,
                    avatarBitmap = event.avatarBitmap,
                    contentIntent = event.contentIntent,
                    durationText = event.durationText,
                    hangUpIntent = event.hangUpIntent,
                    speakerIntent = event.speakerIntent,
                    muteIntent = event.muteIntent,
                    accentColor = event.accentColor,
                    // Preserve expanded state across updates
                    startTimeMs = prevOngoing?.startTimeMs ?: nowMs
                )
            }
            is IslandEvent.CallEnded -> null // Signal to dismiss
            else -> prev
        }
    }

    override fun priority(state: Any?): Int {
        return when (state) {
            is CallState.Incoming -> ActiveIsland.PRIORITY_INCOMING_CALL
            is CallState.Ongoing -> ActiveIsland.PRIORITY_ONGOING_CALL
            else -> ActiveIsland.PRIORITY_ONGOING_CALL
        }
    }

    override fun route(state: Any?): IslandRoute {
        // Calls use APP_OVERLAY for full control over actions
        return IslandRoute.APP_OVERLAY
    }

    override fun policy(state: Any?): IslandPolicy {
        return when (state) {
            is CallState.Incoming -> IslandPolicy.INCOMING_CALL
            is CallState.Ongoing -> IslandPolicy.ONGOING_CALL
            else -> IslandPolicy.ONGOING_CALL
        }
    }

    @Composable
    override fun Render(
        state: Any?,
        uiState: FeatureUiState,
        actions: IslandActions
    ) {
        val callState = state as? CallState ?: return
        val rid = uiState.debugRid
        val context = LocalContext.current

        when (callState) {
            is CallState.Incoming -> {
                IncomingCallPill(
                    title = callState.title,
                    name = callState.callerName,
                    avatarBitmap = callState.avatarBitmap,
                    accentColor = callState.accentColor,
                    onDecline = {
                        if (BuildConfig.DEBUG) {
                            HiLog.d(HiLog.TAG_INPUT, "RID=$rid EVT=INPUT_DECLINE_CLICK")
                        }
                        val intent = Intent(context, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_HANGUP }
                        val pi = PendingIntent.getBroadcast(context, 101, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        actions.endCall(callState.declineIntent ?: pi)
                        actions.dismiss("CALL_DECLINE")
                    },
                    onAccept = {
                        if (BuildConfig.DEBUG) {
                            HiLog.d(HiLog.TAG_INPUT, "RID=$rid EVT=INPUT_ACCEPT_CLICK")
                        }
                        val intent = Intent(context, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_ACCEPT }
                        val pi = PendingIntent.getBroadcast(context, 102, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        actions.acceptCall(callState.acceptIntent ?: pi)
                        // Don't dismiss - wait for ONGOING state
                    },
                    debugRid = rid
                )
            }
            is CallState.Ongoing -> {
                if (uiState.isExpanded) {
                    ActiveCallExpandedPill(
                        callerLabel = callState.callerName,
                        durationText = callState.durationText,
                        onHangUp = {
                            val intent = Intent(context, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_HANGUP }
                            val pi = PendingIntent.getBroadcast(context, 103, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            actions.endCall(callState.hangUpIntent ?: pi)
                        },
                        onSpeaker = {
                            val intent = Intent(context, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_SPEAKER }
                            val pi = PendingIntent.getBroadcast(context, 104, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            actions.toggleSpeaker(callState.speakerIntent ?: pi)
                        },
                        onMute = {
                            val intent = Intent(context, CallActionReceiver::class.java).apply { action = CallActionReceiver.ACTION_MUTE }
                            val pi = PendingIntent.getBroadcast(context, 105, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                            actions.toggleMute(callState.muteIntent ?: pi)
                        },
                        debugRid = rid
                    )
                } else {
                    ActiveCallCompactPill(
                        callerLabel = callState.callerName,
                        durationText = callState.durationText,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (BuildConfig.DEBUG) {
                                    HiLog.d(HiLog.TAG_INPUT, "RID=$rid EVT=CALL_COMPACT_TAP")
                                }
                                actions.showInCallScreen()
                            },
                            onLongClick = {
                                actions.expand()
                            }
                        ),
                        debugRid = rid
                    )
                }
            }
        }
    }
}

/**
 * Call state variants.
 */
sealed class CallState {
    abstract val notificationKey: String
    abstract val packageName: String
    abstract val callerName: String
    abstract val avatarBitmap: Bitmap?
    abstract val contentIntent: PendingIntent?
    abstract val accentColor: String?

    data class Incoming(
        override val notificationKey: String,
        override val packageName: String,
        override val callerName: String,
        override val avatarBitmap: Bitmap? = null,
        override val contentIntent: PendingIntent? = null,
        override val accentColor: String? = null,
        val title: String,
        val acceptIntent: PendingIntent? = null,
        val declineIntent: PendingIntent? = null
    ) : CallState()

    data class Ongoing(
        override val notificationKey: String,
        override val packageName: String,
        override val callerName: String,
        override val avatarBitmap: Bitmap? = null,
        override val contentIntent: PendingIntent? = null,
        override val accentColor: String? = null,
        val durationText: String = "",
        val hangUpIntent: PendingIntent? = null,
        val speakerIntent: PendingIntent? = null,
        val muteIntent: PendingIntent? = null,
        val startTimeMs: Long = System.currentTimeMillis()
    ) : CallState()
}
