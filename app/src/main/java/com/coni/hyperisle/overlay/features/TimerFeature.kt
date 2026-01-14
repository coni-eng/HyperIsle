package com.coni.hyperisle.overlay.features

import android.app.PendingIntent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.overlay.engine.ActiveIsland
import com.coni.hyperisle.overlay.engine.IslandActions
import com.coni.hyperisle.overlay.engine.IslandEvent
import com.coni.hyperisle.overlay.engine.IslandPolicy
import com.coni.hyperisle.overlay.engine.IslandRoute
import com.coni.hyperisle.ui.components.TimerPill



/**
 * Feature for handling timer/chronometer islands.
 * Displays countdown or elapsed time from timer apps.
 */
class TimerFeature : IslandFeature {
    override val id: String = "timer"

    override fun canHandle(event: IslandEvent): Boolean {
        return event is IslandEvent.Timer
    }

    override fun reduce(prev: Any?, event: IslandEvent, nowMs: Long): Any? {
        val timerEvent = event as? IslandEvent.Timer ?: return prev
        
        return TimerState(
            notificationKey = timerEvent.notificationKey,
            packageName = timerEvent.packageName,
            label = timerEvent.label,
            baseTimeMs = timerEvent.baseTimeMs,
            isCountdown = timerEvent.isCountdown,
            contentIntent = timerEvent.contentIntent,
            accentColor = timerEvent.accentColor
        )
    }

    override fun priority(state: Any?): Int = ActiveIsland.PRIORITY_TIMER_RINGING

    override fun route(state: Any?): IslandRoute = IslandRoute.APP_OVERLAY

    override fun policy(state: Any?): IslandPolicy = IslandPolicy.TIMER

    @Composable
    override fun Render(
        state: Any?,
        uiState: FeatureUiState,
        actions: IslandActions
    ) {
        val timerState = state as? TimerState ?: return
        val rid = uiState.debugRid

        TimerPill(
            label = timerState.label,
            baseTimeMs = timerState.baseTimeMs,
            isCountdown = timerState.isCountdown,
            accentColor = timerState.accentColor,
            modifier = Modifier
                .widthIn(min = 160.dp, max = 220.dp)
                .combinedClickable(
                    onClick = {
                        timerState.contentIntent?.let { intent ->
                            actions.sendPendingIntent(intent, "timer_tap")
                        } ?: actions.openApp(timerState.packageName)
                    }
                ),
            debugRid = rid
        )
    }
}

/**
 * Timer state.
 */
data class TimerState(
    val notificationKey: String,
    val packageName: String,
    val label: String,
    val baseTimeMs: Long,
    val isCountdown: Boolean,
    val contentIntent: PendingIntent? = null,
    val accentColor: String? = null
)
