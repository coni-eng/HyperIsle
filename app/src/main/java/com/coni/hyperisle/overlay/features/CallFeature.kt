package com.coni.hyperisle.overlay.features

import android.app.PendingIntent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.overlay.engine.ActiveIsland
import com.coni.hyperisle.overlay.engine.IslandActions
import com.coni.hyperisle.overlay.engine.IslandEvent
import com.coni.hyperisle.overlay.engine.IslandPolicy
import com.coni.hyperisle.overlay.engine.IslandRoute
import com.coni.hyperisle.ui.components.ActiveCallCompactPill
import com.coni.hyperisle.ui.components.ActiveCallExpandedPill
import com.coni.hyperisle.ui.components.IncomingCallPill

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

        when (callState) {
            is CallState.Incoming -> {
                IncomingCallPill(
                    title = callState.title,
                    name = callState.callerName,
                    avatarBitmap = callState.avatarBitmap,
                    accentColor = callState.accentColor,
                    onDecline = {
                        if (BuildConfig.DEBUG) {
                            Log.d("HI_INPUT", "RID=$rid EVT=INPUT_DECLINE_CLICK")
                        }
                        actions.endCall(callState.declineIntent)
                        actions.dismiss("CALL_DECLINE")
                    },
                    onAccept = {
                        if (BuildConfig.DEBUG) {
                            Log.d("HI_INPUT", "RID=$rid EVT=INPUT_ACCEPT_CLICK")
                        }
                        actions.acceptCall(callState.acceptIntent)
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
                            actions.endCall(callState.hangUpIntent)
                        },
                        onSpeaker = {
                            actions.toggleSpeaker(callState.speakerIntent)
                        },
                        onMute = {
                            actions.toggleMute(callState.muteIntent)
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
                                    Log.d("HI_INPUT", "RID=$rid EVT=CALL_COMPACT_TAP")
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
