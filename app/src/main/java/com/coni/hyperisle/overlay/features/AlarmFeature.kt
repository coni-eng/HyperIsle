package com.coni.hyperisle.overlay.features

import android.app.PendingIntent
import android.util.Log
import com.coni.hyperisle.util.HiLog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.R
import com.coni.hyperisle.overlay.engine.ActiveIsland
import com.coni.hyperisle.overlay.engine.IslandActions
import com.coni.hyperisle.overlay.engine.IslandEvent
import com.coni.hyperisle.overlay.engine.IslandPolicy
import com.coni.hyperisle.overlay.engine.IslandRoute

/**
 * Feature for handling alarm ringing islands.
 * Displays alarm with dismiss/snooze actions.
 */
class AlarmFeature : IslandFeature {
    override val id: String = "alarm"

    override fun canHandle(event: IslandEvent): Boolean {
        return event is IslandEvent.Alarm
    }

    override fun reduce(prev: Any?, event: IslandEvent, nowMs: Long): Any? {
        val alarmEvent = event as? IslandEvent.Alarm ?: return prev
        
        return AlarmState(
            notificationKey = alarmEvent.notificationKey,
            packageName = alarmEvent.packageName,
            label = alarmEvent.label,
            timeLabel = alarmEvent.timeLabel,
            dismissIntent = alarmEvent.dismissIntent,
            snoozeIntent = alarmEvent.snoozeIntent,
            contentIntent = alarmEvent.contentIntent,
            accentColor = alarmEvent.accentColor
        )
    }

    override fun priority(state: Any?): Int = ActiveIsland.PRIORITY_ALARM

    override fun route(state: Any?): IslandRoute = IslandRoute.APP_OVERLAY

    override fun policy(state: Any?): IslandPolicy = IslandPolicy.ALARM

    @Composable
    override fun Render(
        state: Any?,
        uiState: FeatureUiState,
        actions: IslandActions
    ) {
        val alarmState = state as? AlarmState ?: return
        val rid = uiState.debugRid

        AlarmPill(
            label = alarmState.label,
            timeLabel = alarmState.timeLabel,
            onDismiss = {
                if (BuildConfig.DEBUG) {
                    Log.d("HI_INPUT", "RID=$rid EVT=ALARM_DISMISS_CLICK")
                }
                alarmState.dismissIntent?.let { intent ->
                    actions.sendPendingIntent(intent, "alarm_dismiss")
                }
                actions.dismiss("ALARM_DISMISS")
            },
            onSnooze = if (alarmState.snoozeIntent != null) {
                {
                    if (BuildConfig.DEBUG) {
                        HiLog.d(HiLog.TAG_INPUT, "RID=$rid EVT=ALARM_SNOOZE_CLICK")
                    }
                    actions.sendPendingIntent(alarmState.snoozeIntent, "alarm_snooze")
                    actions.dismiss("ALARM_SNOOZE")
                }
            } else null,
            debugRid = rid
        )
    }

    @Composable
    private fun AlarmPill(
        label: String,
        timeLabel: String,
        onDismiss: () -> Unit,
        onSnooze: (() -> Unit)?,
        debugRid: Int
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50.dp),
            color = Color(0xCC000000)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Alarm icon and info
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFFFF9500), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⏰",
                            fontSize = 20.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = timeLabel,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (label.isNotEmpty()) {
                            Text(
                                text = label,
                                color = Color(0xFF8E8E93),
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Action buttons
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Snooze button (if available)
                    if (onSnooze != null) {
                        Surface(
                            onClick = onSnooze,
                            shape = CircleShape,
                            color = Color(0xFF2C2C2E)
                        ) {
                            Box(
                                modifier = Modifier.size(44.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "💤",
                                    fontSize = 18.sp
                                )
                            }
                        }
                    }

                    // Dismiss button
                    Surface(
                        onClick = onDismiss,
                        shape = CircleShape,
                        color = Color(0xFFFF3B30)
                    ) {
                        Box(
                            modifier = Modifier.size(44.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_action_close),
                                contentDescription = "Dismiss",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Alarm state.
 */
data class AlarmState(
    val notificationKey: String,
    val packageName: String,
    val label: String,
    val timeLabel: String,
    val dismissIntent: PendingIntent? = null,
    val snoozeIntent: PendingIntent? = null,
    val contentIntent: PendingIntent? = null,
    val accentColor: String? = null
)
