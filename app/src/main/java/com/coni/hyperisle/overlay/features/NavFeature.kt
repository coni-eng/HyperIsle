package com.coni.hyperisle.overlay.features

import android.app.PendingIntent
import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coni.hyperisle.overlay.engine.ActiveIsland
import com.coni.hyperisle.overlay.engine.IslandActions
import com.coni.hyperisle.overlay.engine.IslandEvent
import com.coni.hyperisle.overlay.engine.IslandPolicy
import com.coni.hyperisle.overlay.engine.IslandRoute

/**
 * Feature for handling navigation islands.
 * Displays turn-by-turn directions from maps apps.
 */
class NavFeature : IslandFeature {
    override val id: String = "navigation"

    override fun canHandle(event: IslandEvent): Boolean {
        return event is IslandEvent.Navigation
    }

    override fun reduce(prev: Any?, event: IslandEvent, nowMs: Long): Any? {
        val navEvent = event as? IslandEvent.Navigation ?: return prev
        
        return NavState(
            notificationKey = navEvent.notificationKey,
            packageName = navEvent.packageName,
            instruction = navEvent.instruction,
            distance = navEvent.distance,
            eta = navEvent.eta,
            remainingTime = navEvent.remainingTime,
            totalDistance = navEvent.totalDistance,
            turnDistance = navEvent.turnDistance,
            directionIcon = navEvent.directionIcon,
            appIcon = navEvent.appIcon,
            contentIntent = navEvent.contentIntent,
            isCompact = navEvent.isCompact,
            accentColor = navEvent.accentColor
        )
    }

    override fun priority(state: Any?): Int {
        val navState = state as? NavState
        // Compact mode = navigation moment (non-sticky), full = active guidance (sticky)
        return if (navState?.isCompact == true) {
            ActiveIsland.PRIORITY_NAVIGATION_MOMENT
        } else {
            ActiveIsland.PRIORITY_NAVIGATION_ACTIVE
        }
    }

    override fun route(state: Any?): IslandRoute = IslandRoute.APP_OVERLAY

    override fun policy(state: Any?): IslandPolicy {
        val navState = state as? NavState
        // Navigation moment (compact) is non-sticky with shorter minVisible
        return if (navState?.isCompact == true) {
            IslandPolicy.NAVIGATION.copy(
                sticky = false,
                minVisibleMs = 1500L
            )
        } else {
            IslandPolicy.NAVIGATION // Active guidance is sticky by default
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Render(
        state: Any?,
        uiState: FeatureUiState,
        actions: IslandActions
    ) {
        val navState = state as? NavState ?: return
        val rid = uiState.debugRid

        NavigationPill(
            instruction = navState.instruction,
            distance = navState.distance,
            eta = navState.eta,
            remainingTime = navState.remainingTime,
            appIcon = navState.appIcon,
            isCompact = navState.isCompact,
            modifier = Modifier
                .widthIn(min = 200.dp, max = 340.dp)
                .combinedClickable(
                    onClick = {
                        if (com.coni.hyperisle.BuildConfig.DEBUG) {
                            com.coni.hyperisle.util.HiLog.d(
                                com.coni.hyperisle.util.HiLog.TAG_INPUT,
                                "RID=$rid EVT=NAV_TAP pkg=${navState.packageName}"
                            )
                        }
                        navState.contentIntent?.let { intent ->
                            actions.sendPendingIntent(intent, "NAV_TAP")
                        } ?: actions.openApp(navState.packageName)
                    },
                    onLongClick = {
                        // Optional: Expand/Collapse logic if needed
                    }
                ),
            debugRid = rid
        )
    }

    @Composable
    private fun NavigationPill(
        instruction: String,
        distance: String,
        eta: String,
        remainingTime: String,
        appIcon: Bitmap?,
        isCompact: Boolean,
        modifier: Modifier = Modifier,
        debugRid: Int = 0
    ) {
        Surface(
            color = Color.Black,
            shape = RoundedCornerShape(50),
            modifier = modifier
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Direction icon or app icon
                if (appIcon != null) {
                    Image(
                        bitmap = appIcon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(Color(0xFF34C759), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "→",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Navigation info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = instruction,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!isCompact && remainingTime.isNotEmpty()) {
                        Text(
                            text = remainingTime,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }

                // Distance and ETA
                Column(horizontalAlignment = Alignment.End) {
                    if (distance.isNotEmpty()) {
                        Text(
                            text = distance,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (eta.isNotEmpty()) {
                        Text(
                            text = eta,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Navigation state.
 */
data class NavState(
    val notificationKey: String,
    val packageName: String,
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
)
