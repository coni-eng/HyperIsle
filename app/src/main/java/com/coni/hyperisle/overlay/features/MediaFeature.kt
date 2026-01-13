package com.coni.hyperisle.overlay.features

import android.app.PendingIntent
import android.graphics.Bitmap
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.overlay.engine.ActiveIsland
import com.coni.hyperisle.overlay.engine.IslandActions
import com.coni.hyperisle.overlay.engine.IslandEvent
import com.coni.hyperisle.overlay.engine.IslandPolicy
import com.coni.hyperisle.overlay.engine.IslandRoute
import com.coni.hyperisle.ui.components.MediaExpandedPill
import com.coni.hyperisle.ui.components.MediaPill
import com.coni.hyperisle.overlay.MediaAction as OverlayMediaAction

/**
 * Feature for handling media islands.
 * Displays music/video playback info.
 * 
 * NOTE: By default routes to SYSTEM_MEDIA to preserve existing behavior
 * where HyperOS native media island is used.
 */
class MediaFeature(
    private val useSystemMedia: () -> Boolean = { true }
) : IslandFeature {
    override val id: String = "media"

    override fun canHandle(event: IslandEvent): Boolean {
        return event is IslandEvent.Media
    }

    override fun reduce(prev: Any?, event: IslandEvent, nowMs: Long): Any? {
        val mediaEvent = event as? IslandEvent.Media ?: return prev
        
        return MediaState(
            notificationKey = mediaEvent.notificationKey,
            packageName = mediaEvent.packageName,
            title = mediaEvent.title,
            subtitle = mediaEvent.subtitle,
            albumArt = mediaEvent.albumArt,
            actions = mediaEvent.actions.map { 
                MediaState.Action(it.label, it.iconBitmap, it.actionIntent)
            },
            contentIntent = mediaEvent.contentIntent,
            isVideo = mediaEvent.isVideo,
            accentColor = mediaEvent.accentColor
        )
    }

    override fun priority(state: Any?): Int = ActiveIsland.PRIORITY_MEDIA

    override fun route(state: Any?): IslandRoute {
        // Preserve existing behavior: use system media island when configured
        return if (useSystemMedia()) IslandRoute.SYSTEM_MEDIA else IslandRoute.APP_OVERLAY
    }

    override fun policy(state: Any?): IslandPolicy = IslandPolicy.MEDIA

    @Composable
    override fun Render(
        state: Any?,
        uiState: FeatureUiState,
        actions: IslandActions
    ) {
        val mediaState = state as? MediaState ?: return
        val rid = uiState.debugRid

        if (uiState.isExpanded) {
            MediaExpandedPill(
                title = mediaState.title,
                subtitle = mediaState.subtitle,
                albumArt = mediaState.albumArt,
                actions = mediaState.actions.map { action ->
                    OverlayMediaAction(
                        label = action.label,
                        iconBitmap = action.iconBitmap,
                        actionIntent = action.actionIntent
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                debugRid = rid
            )
        } else {
            MediaPill(
                title = mediaState.title,
                subtitle = mediaState.subtitle,
                albumArt = mediaState.albumArt,
                accentColor = mediaState.accentColor,
                modifier = Modifier
                    .widthIn(min = 220.dp, max = 280.dp)
                    .combinedClickable(
                        onClick = {
                            mediaState.contentIntent?.let { intent ->
                                actions.sendPendingIntent(intent, "media_tap")
                            } ?: actions.openApp(mediaState.packageName)
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

/**
 * Media state.
 */
data class MediaState(
    val notificationKey: String,
    val packageName: String,
    val title: String,
    val subtitle: String,
    val albumArt: Bitmap? = null,
    val actions: List<Action> = emptyList(),
    val contentIntent: PendingIntent? = null,
    val isVideo: Boolean = false,
    val accentColor: String? = null
) {
    data class Action(
        val label: String,
        val iconBitmap: Bitmap? = null,
        val actionIntent: PendingIntent
    )
}
