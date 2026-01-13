package com.coni.hyperisle.overlay.features

import android.app.PendingIntent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Feature for handling standard/progress islands.
 * Fallback for notifications that don't match other features.
 */
class StandardFeature : IslandFeature {
    override val id: String = "standard"

    override fun canHandle(event: IslandEvent): Boolean {
        return event is IslandEvent.Standard || event is IslandEvent.Progress
    }

    override fun reduce(prev: Any?, event: IslandEvent, nowMs: Long): Any? {
        return when (event) {
            is IslandEvent.Progress -> StandardState(
                notificationKey = event.notificationKey,
                packageName = event.packageName,
                title = event.title,
                text = event.text,
                icon = null,
                contentIntent = event.contentIntent,
                accentColor = event.accentColor,
                progress = event.progress,
                maxProgress = event.maxProgress,
                hasProgress = true
            )
            is IslandEvent.Standard -> StandardState(
                notificationKey = event.notificationKey,
                packageName = event.packageName,
                title = event.title,
                text = event.text,
                icon = event.icon,
                contentIntent = event.contentIntent,
                accentColor = event.accentColor,
                progress = 0,
                maxProgress = 0,
                hasProgress = false
            )
            else -> prev
        }
    }

    override fun priority(state: Any?): Int {
        // Both progress and standard use FALLBACK priority (lowest)
        return ActiveIsland.PRIORITY_FALLBACK
    }

    override fun route(state: Any?): IslandRoute = IslandRoute.APP_OVERLAY

    override fun policy(state: Any?): IslandPolicy = IslandPolicy.STANDARD

    @Composable
    override fun Render(
        state: Any?,
        uiState: FeatureUiState,
        actions: IslandActions
    ) {
        val standardState = state as? StandardState ?: return
        val rid = uiState.debugRid

        if (standardState.hasProgress) {
            ProgressPill(
                title = standardState.title,
                text = standardState.text,
                progress = standardState.progress,
                maxProgress = standardState.maxProgress,
                icon = standardState.icon,
                debugRid = rid
            )
        } else {
            StandardPill(
                title = standardState.title,
                text = standardState.text,
                icon = standardState.icon,
                debugRid = rid
            )
        }
    }

    @Composable
    private fun StandardPill(
        title: String,
        text: String,
        icon: Bitmap?,
        debugRid: Int
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50.dp),
            color = Color(0xCC000000)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2C2C2E)),
                    contentAlignment = Alignment.Center
                ) {
                    if (icon != null) {
                        Image(
                            bitmap = icon.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Text content
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (text.isNotEmpty()) {
                        Text(
                            text = text,
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ProgressPill(
        title: String,
        text: String,
        progress: Int,
        maxProgress: Int,
        icon: Bitmap?,
        debugRid: Int
    ) {
        val progressFraction = if (maxProgress > 0) {
            progress.toFloat() / maxProgress.toFloat()
        } else {
            0f
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50.dp),
            color = Color(0xCC000000)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Icon
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2C2C2E)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (icon != null) {
                            Image(
                                bitmap = icon.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Text content
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (text.isNotEmpty()) {
                            Text(
                                text = text,
                                color = Color(0xFF8E8E93),
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Percentage
                    Text(
                        text = "${(progressFraction * 100).toInt()}%",
                        color = Color(0xFF34C759),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Progress bar
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF34C759),
                    trackColor = Color(0xFF3A3A3C)
                )
            }
        }
    }
}

/**
 * Standard/Progress state.
 */
data class StandardState(
    val notificationKey: String,
    val packageName: String,
    val title: String,
    val text: String,
    val icon: Bitmap? = null,
    val contentIntent: PendingIntent? = null,
    val accentColor: String? = null,
    val progress: Int = 0,
    val maxProgress: Int = 0,
    val hasProgress: Boolean = false
)
