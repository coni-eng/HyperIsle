package com.coni.hyperisle.ui.components

import android.app.PendingIntent
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.R
import com.coni.hyperisle.overlay.MediaAction
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private data class LayoutSnapshot(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

@Composable
private fun Modifier.debugLayoutModifier(rid: Int?, element: String): Modifier {
    if (!BuildConfig.DEBUG || rid == null) return this
    var lastSnapshot by remember { mutableStateOf<LayoutSnapshot?>(null) }      
    return this.onGloballyPositioned { coords ->
        val pos = coords.positionInRoot()
        val snapshot = LayoutSnapshot(
            x = pos.x.roundToInt(),
            y = pos.y.roundToInt(),
            width = coords.size.width,
            height = coords.size.height
        )
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=UI_LAYOUT element=$element x=${snapshot.x} y=${snapshot.y} w=${snapshot.width} h=${snapshot.height}"
            )
        }
    }
}

/**
 * iOS-style pill container with rounded corners and semi-transparent black background.
 * Used as the base container for all pill-style overlays.
 */
@Composable
fun PillContainer(
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
    fillMaxWidth: Boolean = true,
    debugRid: Int? = null,
    debugName: String = "pill",
    content: @Composable () -> Unit
) {
    val containerModifier = if (fillMaxWidth) modifier.fillMaxWidth() else modifier
    Surface(
        modifier = containerModifier
            .height(height)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(50.dp))
            .debugLayoutModifier(debugRid, "${debugName}_root"),
        shape = RoundedCornerShape(50.dp),
        color = Color(0xCC000000)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/**
 * iOS-style incoming call pill with avatar, caller info, and accept/decline buttons.
 * 
 * @param title Small grey text above the name (e.g., "Incoming Call")
 * @param name Bold white text showing caller name
 * @param avatarBitmap Optional avatar bitmap, shows placeholder if null
 * @param onDecline Callback when decline (red) button is tapped
 * @param onAccept Callback when accept (green) button is tapped
 */
@Composable
fun IncomingCallPill(
    title: String,
    name: String,
    avatarBitmap: Bitmap? = null,
    onDecline: () -> Unit,
    onAccept: () -> Unit,
    debugRid: Int? = null
) {
    if (BuildConfig.DEBUG && debugRid != null) {
        val hasAvatar = avatarBitmap != null
        LaunchedEffect(title, name, hasAvatar) {
            Log.d(
                "HyperIsleIsland",
                "RID=$debugRid EVT=UI_CONTENT type=CALL titleLen=${title.length} nameLen=${name.length} hasAvatar=$hasAvatar"
            )
        }
    }
    PillContainer(debugRid = debugRid, debugName = "call") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .debugLayoutModifier(debugRid, "call_row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A3A3C))
                    .debugLayoutModifier(debugRid, "call_avatar"),
                contentAlignment = Alignment.Center
            ) {
                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap.asImageBitmap(),
                        contentDescription = "Caller avatar",
                        modifier = Modifier.size(44.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Default avatar",
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Center: Title and Name
            Column(
                modifier = Modifier
                    .weight(1f)
                    .debugLayoutModifier(debugRid, "call_text_column"),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    color = Color(0xFF8E8E93),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.debugLayoutModifier(debugRid, "call_title")
                )
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.debugLayoutModifier(debugRid, "call_name")
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Right: Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decline button
                Box(
                    modifier = Modifier
                        .size(55.dp)
                        .debugLayoutModifier(debugRid, "call_decline_btn")
                        .clickable { onDecline() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.call_end),
                        contentDescription = "Decline call",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(85.dp)
                    )
                }

                // Accept button
                Box(
                    modifier = Modifier
                        .size(55.dp)
                        .debugLayoutModifier(debugRid, "call_accept_btn")
                        .clickable { onAccept() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.call),
                        contentDescription = "Accept call",
                        tint = Color.Unspecified,
                        modifier = Modifier.size(85.dp)
                    )
                }
            }
        }
    }
}

/**
 * Compact active call pill showing caller label and duration.
 * Sleek iOS-style mini pill with phone icon indicator.
 */
@Composable
fun ActiveCallCompactPill(
    callerLabel: String,
    durationText: String,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = true,
    debugRid: Int? = null
) {
    val containerModifier = if (fillMaxWidth) modifier.fillMaxWidth() else modifier
    Surface(
        modifier = containerModifier
            .height(52.dp)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(26.dp))
            .debugLayoutModifier(debugRid, "call_compact_root"),
        shape = RoundedCornerShape(26.dp),
        color = Color(0xE6000000)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .debugLayoutModifier(debugRid, "call_compact_row"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Phone icon indicator
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF34C759)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = "Active call",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Caller name
            Text(
                text = callerLabel,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .debugLayoutModifier(debugRid, "call_compact_name")
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Duration with green color
            Text(
                text = durationText,
                color = Color(0xFF34C759),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.debugLayoutModifier(debugRid, "call_compact_duration")
            )
        }
    }
}

/**
 * Expanded active call pill with call controls.
 * Modern iOS-style design with centered caller info and action buttons.
 */
@Composable
fun ActiveCallExpandedPill(
    callerLabel: String,
    durationText: String,
    onHangUp: (() -> Unit)?,
    onSpeaker: (() -> Unit)?,
    onMute: (() -> Unit)?,
    debugRid: Int? = null
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(36.dp))
            .debugLayoutModifier(debugRid, "call_active_root"),
        shape = RoundedCornerShape(36.dp),
        color = Color(0xE6000000)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Caller info - centered
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.debugLayoutModifier(debugRid, "call_active_header")
            ) {
                Text(
                    text = callerLabel,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.debugLayoutModifier(debugRid, "call_active_name")
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = durationText,
                    color = Color(0xFF34C759),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    modifier = Modifier.debugLayoutModifier(debugRid, "call_active_duration")
                )
            }

            // Action buttons row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .debugLayoutModifier(debugRid, "call_active_actions"),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Hang up button (red, larger)
                CallActionButton(
                    icon = Icons.Default.CallEnd,
                    contentDescription = "End call",
                    background = Color(0xFFFF3B30),
                    onClick = onHangUp,
                    modifier = Modifier.debugLayoutModifier(debugRid, "call_active_end")
                )
                // Speaker button
                CallActionButton(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = "Speaker",
                    background = Color(0xFF48484A),
                    onClick = onSpeaker,
                    modifier = Modifier.debugLayoutModifier(debugRid, "call_active_speaker")
                )
                // Mute button
                CallActionButton(
                    icon = Icons.Default.MicOff,
                    contentDescription = "Mute",
                    background = Color(0xFF48484A),
                    onClick = onMute,
                    modifier = Modifier.debugLayoutModifier(debugRid, "call_active_mute")
                )
            }
        }
    }
}

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    background: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val enabled = onClick != null

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(background)
            .then(
                if (enabled) {
                    Modifier.clickable { onClick?.invoke() }
                } else {
                    Modifier
                }
            )
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(22.dp)
        )
    }
}

/**
 * Compact media pill with album art and waveform.
 */
@Composable
fun MediaPill(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    albumArt: Bitmap? = null,
    debugRid: Int? = null
) {
    if (BuildConfig.DEBUG && debugRid != null) {
        val hasArt = albumArt != null
        LaunchedEffect(title, subtitle, hasArt) {
            Log.d(
                "HyperIsleIsland",
                "RID=$debugRid EVT=UI_CONTENT type=MEDIA titleLen=${title.length} subtitleLen=${subtitle.length} hasArt=$hasArt"
            )
        }
    }
    PillContainer(
        modifier = modifier,
        height = 48.dp,
        fillMaxWidth = false,
        debugRid = debugRid,
        debugName = "media_compact"
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .debugLayoutModifier(debugRid, "media_row"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MediaArtwork(
                albumArt = albumArt,
                size = 28.dp,
                debugRid = debugRid
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .debugLayoutModifier(debugRid, "media_text_column"),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.debugLayoutModifier(debugRid, "media_title")
                )
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        color = Color(0xFF8E8E93),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.debugLayoutModifier(debugRid, "media_subtitle")
                    )
                }
            }

            WaveformIndicator(
                modifier = Modifier.debugLayoutModifier(debugRid, "media_waveform")
            )
        }
    }
}

/**
 * Expanded media pill with actions.
 */
@Composable
fun MediaExpandedPill(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    albumArt: Bitmap? = null,
    actions: List<MediaAction> = emptyList(),
    debugRid: Int? = null
) {
    if (BuildConfig.DEBUG && debugRid != null) {
        val hasArt = albumArt != null
        LaunchedEffect(title, subtitle, hasArt, actions.size) {
            Log.d(
                "HyperIsleIsland",
                "RID=$debugRid EVT=UI_CONTENT type=MEDIA_EXPANDED titleLen=${title.length} subtitleLen=${subtitle.length} actions=${actions.size} hasArt=$hasArt"
            )
        }
    }
    PillContainer(
        modifier = modifier,
        height = 84.dp,
        debugRid = debugRid,
        debugName = "media_expanded"
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .debugLayoutModifier(debugRid, "media_expanded_header"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MediaArtwork(
                    albumArt = albumArt,
                    size = 36.dp,
                    debugRid = debugRid
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .debugLayoutModifier(debugRid, "media_expanded_text"),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.debugLayoutModifier(debugRid, "media_expanded_title")
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            color = Color(0xFF8E8E93),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.debugLayoutModifier(debugRid, "media_expanded_subtitle")
                        )
                    }
                }
            }

            if (actions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .debugLayoutModifier(debugRid, "media_expanded_actions"),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actions.take(3).forEach { action ->
                        MediaActionButton(
                            action = action,
                            debugRid = debugRid
                        )
                    }
                }
            }
        }
    }
}

/**
 * Small circular media dot with album art.
 */
@Composable
fun MediaDot(
    modifier: Modifier = Modifier,
    albumArt: Bitmap? = null,
    size: Dp = 36.dp,
    debugRid: Int? = null
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xCC000000))
            .debugLayoutModifier(debugRid, "media_dot"),
        contentAlignment = Alignment.Center
    ) {
        if (albumArt != null) {
            Image(
                bitmap = albumArt.asImageBitmap(),
                contentDescription = "Album art",
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = "Music",
                tint = Color(0xFF8E8E93),
                modifier = Modifier.size(size * 0.45f)
            )
        }
    }
}

/**
 * Small circular timer dot with live time.
 */
@Composable
fun TimerDot(
    baseTimeMs: Long,
    isCountdown: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    debugRid: Int? = null
) {
    val timerText by produceState(initialValue = "00:00", baseTimeMs, isCountdown) {
        while (true) {
            val now = System.currentTimeMillis()
            val deltaMs = if (isCountdown) max(0L, baseTimeMs - now) else now - baseTimeMs
            value = formatDuration(deltaMs / 1000)
            delay(1000L)
        }
    }
    if (BuildConfig.DEBUG && debugRid != null) {
        LaunchedEffect(timerText) {
            Log.d(
                "HyperIsleIsland",
                "RID=$debugRid EVT=UI_CONTENT type=TIMER textLen=${timerText.length} countdown=$isCountdown"
            )
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xCC000000))
            .debugLayoutModifier(debugRid, "timer_dot"),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = timerText,
            color = Color.White,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

/**
 * Timer pill for standalone timer activity.
 */
@Composable
fun TimerPill(
    label: String,
    baseTimeMs: Long,
    isCountdown: Boolean,
    modifier: Modifier = Modifier,
    debugRid: Int? = null
) {
    val timerText by produceState(initialValue = "00:00", baseTimeMs, isCountdown) {
        while (true) {
            val now = System.currentTimeMillis()
            val deltaMs = if (isCountdown) max(0L, baseTimeMs - now) else now - baseTimeMs
            value = formatDuration(deltaMs / 1000)
            delay(1000L)
        }
    }
    PillContainer(
        modifier = modifier,
        height = 48.dp,
        fillMaxWidth = false,
        debugRid = debugRid,
        debugName = "timer_pill"
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .debugLayoutModifier(debugRid, "timer_row"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A3A3C))
                    .debugLayoutModifier(debugRid, "timer_icon"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AccessTime,
                    contentDescription = "Timer",
                    tint = Color(0xFF8E8E93),
                    modifier = Modifier.size(14.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .debugLayoutModifier(debugRid, "timer_text_column"),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.debugLayoutModifier(debugRid, "timer_label")
                )
                Text(
                    text = timerText,
                    color = Color(0xFF34C759),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.debugLayoutModifier(debugRid, "timer_value")
                )
            }
        }
    }
}

@Composable
private fun MediaArtwork(
    albumArt: Bitmap?,
    size: Dp,
    debugRid: Int?
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0xFF3A3A3C))
            .debugLayoutModifier(debugRid, "media_artwork"),
        contentAlignment = Alignment.Center
    ) {
        if (albumArt != null) {
            Image(
                bitmap = albumArt.asImageBitmap(),
                contentDescription = "Album art",
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = "Music",
                tint = Color(0xFF8E8E93),
                modifier = Modifier.size(size * 0.45f)
            )
        }
    }
}

@Composable
private fun WaveformIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "media_waveform")
    val bar1 by transition.animateFloat(
        initialValue = 4f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2 by transition.animateFloat(
        initialValue = 6f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3 by transition.animateFloat(
        initialValue = 5f,
        targetValue = 13f,
        animationSpec = infiniteRepeatable(
            animation = tween(550),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        WaveformBar(height = bar1.dp)
        WaveformBar(height = bar2.dp)
        WaveformBar(height = bar3.dp)
    }
}

@Composable
private fun WaveformBar(height: Dp) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFF34C759))
    )
}

@Composable
private fun MediaActionButton(
    action: MediaAction,
    debugRid: Int? = null
) {
    val label = action.label.ifBlank { "?" }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0xFF3A3A3C))
            .clickable {
                try {
                    action.actionIntent.send()
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=${debugRid ?: 0} EVT=MEDIA_ACTION_OK label=${label.take(12)}"
                        )
                    }
                } catch (e: PendingIntent.CanceledException) {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=${debugRid ?: 0} EVT=MEDIA_ACTION_FAIL label=${label.take(12)} reason=CANCELED"
                        )
                    }
                } catch (e: Exception) {
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=${debugRid ?: 0} EVT=MEDIA_ACTION_FAIL label=${label.take(12)} reason=${e.javaClass.simpleName}"
                        )
                    }
                }
            }
            .debugLayoutModifier(debugRid, "media_action_${label.take(6)}"),
        contentAlignment = Alignment.Center
    ) {
        if (action.iconBitmap != null) {
            Image(
                bitmap = action.iconBitmap.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Text(
                text = label.take(1),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return String.format(Locale.getDefault(), "%d:%02d", minutes, secs)
}

/**
 * iOS-style notification pill with avatar, sender info, and message preview.
 * Designed to match iOS Dynamic Island notification appearance.
 *
 * @param sender Bold white text showing sender/app name
 * @param message Message preview (up to 2 lines)
 * @param avatarBitmap Optional avatar/app icon bitmap
 * @param onLongPress Optional callback when pill is long-pressed
 * @param onClick Optional callback when pill is tapped
 * @param onDismiss Optional callback when close button is tapped
 */
@Composable
fun NotificationPill(
    sender: String,
    timeLabel: String,
    message: String,
    avatarBitmap: Bitmap? = null,
    onLongPress: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    debugRid: Int? = null
) {
    if (BuildConfig.DEBUG && debugRid != null) {
        val hasAvatar = avatarBitmap != null
        val hasDismiss = onDismiss != null
        val hasClick = onClick != null
        val hasLongPress = onLongPress != null
        LaunchedEffect(sender, timeLabel, message, hasAvatar, hasDismiss, hasClick, hasLongPress) {
            Log.d(
                "HyperIsleIsland",
                "RID=$debugRid EVT=UI_CONTENT type=NOTIFICATION senderLen=${sender.length} timeLen=${timeLabel.length} messageLen=${message.length} hasAvatar=$hasAvatar hasDismiss=$hasDismiss hasClick=$hasClick hasLongPress=$hasLongPress"
            )
        }
    }
    val tapModifier = if (onClick != null || onLongPress != null) {
        Modifier.pointerInput(onClick, onLongPress) {
            detectTapGestures(
                onTap = { onClick?.invoke() },
                onLongPress = { onLongPress?.invoke() }
            )
        }
    } else {
        Modifier
    }
    
    // iOS-style notification pill - compact and clean
    Surface(
        modifier = tapModifier
            .fillMaxWidth()
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(24.dp))
            .debugLayoutModifier(debugRid, "notif_root"),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xE61C1C1E) // Slightly more opaque for better readability
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
                .debugLayoutModifier(debugRid, "notif_row"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: App icon (rounded square like iOS)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF3A3A3C))
                    .debugLayoutModifier(debugRid, "notif_avatar"),
                contentAlignment = Alignment.Center
            ) {
                if (avatarBitmap != null) {
                    Image(
                        bitmap = avatarBitmap.asImageBitmap(),
                        contentDescription = "App icon",
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Default icon",
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Center: Sender and message
            Column(
                modifier = Modifier
                    .weight(1f)
                    .debugLayoutModifier(debugRid, "notif_text_column"),
                verticalArrangement = Arrangement.Center
            ) {
                // Sender name - bold
                Text(
                    text = sender,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.debugLayoutModifier(debugRid, "notif_sender")
                )

                // Message preview - 2 lines max for better content visibility
                if (message.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = message,
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp,
                        modifier = Modifier.debugLayoutModifier(debugRid, "notif_message")
                    )
                }
            }

            // Right: Dismiss button (X)
            if (onDismiss != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF48484A))
                        .clickable { onDismiss() }
                        .debugLayoutModifier(debugRid, "notif_dismiss_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss notification",
                        tint = Color(0xFFAEAEB2),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Inline reply pill showing only the reply composer.
 */
@Composable
fun NotificationReplyPill(
    sender: String,
    message: String,
    avatarBitmap: Bitmap? = null,
    replyText: String,
    onReplyChange: (String) -> Unit,
    onSend: () -> Unit,
    sendLabel: String,
    debugRid: Int? = null
) {
    if (BuildConfig.DEBUG && debugRid != null) {
        val hasAvatar = avatarBitmap != null
        LaunchedEffect(sender, message, hasAvatar) {
            Log.d(
                "HyperIsleIsland",
                "RID=$debugRid EVT=UI_CONTENT type=NOTIFICATION_REPLY senderLen=${sender.length} messageLen=${message.length} hasAvatar=$hasAvatar"
            )
        }
    }
    PillContainer(
        height = 80.dp,
        debugRid = debugRid,
        debugName = "notif_reply"
    ) {
        InlineReplyComposer(
            label = sendLabel,
            text = replyText,
            onTextChange = onReplyChange,
            onSend = onSend,
            modifier = Modifier.debugLayoutModifier(debugRid, "notif_reply_composer")
        )
    }
}

/**
 * Compact notification pill used for collapsed overlay state.
 */
@Composable
fun MiniNotificationPill(
    sender: String,
    avatarBitmap: Bitmap? = null,
    onDismiss: (() -> Unit)? = null,
    debugRid: Int? = null
) {
    PillContainer(
        height = 48.dp,
        debugRid = debugRid,
        debugName = "notif_mini"
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .debugLayoutModifier(debugRid, "notif_mini_row"),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .debugLayoutModifier(debugRid, "notif_mini_avatar_stack")
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3A3A3C))
                        .debugLayoutModifier(debugRid, "notif_mini_avatar"),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap.asImageBitmap(),
                            contentDescription = "App icon",
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Default icon",
                            tint = Color(0xFF8E8E93),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .offset(x = 0.dp, y = 22.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1B1B1B))
                        .padding(2.dp)
                        .debugLayoutModifier(debugRid, "notif_mini_indicator_border")
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34C759))
                            .debugLayoutModifier(debugRid, "notif_mini_indicator_dot")
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = sender,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .debugLayoutModifier(debugRid, "notif_mini_sender")
            )

            if (onDismiss != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .debugLayoutModifier(debugRid, "notif_mini_dismiss_btn")
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss notification",
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

// --- PREVIEWS ---

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1E)
@Composable
private fun PreviewPillContainer() {
    Box(modifier = Modifier.padding(16.dp)) {
        PillContainer {
            Text("Sample Content", color = Color.White)
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1E)
@Composable
private fun PreviewIncomingCallPill() {
    Box(modifier = Modifier.padding(16.dp)) {
        IncomingCallPill(
            title = "Incoming Call",
            name = "John Doe",
            avatarBitmap = null,
            onDecline = {},
            onAccept = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1E)
@Composable
private fun PreviewNotificationPill() {
    Box(modifier = Modifier.padding(16.dp)) {
        NotificationPill(
            sender = "WhatsApp",
            timeLabel = "",
            message = "Hey! Are you coming to the party tonight?",
            avatarBitmap = null,
            onClick = {},
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1C1E)
@Composable
private fun PreviewNotificationPillWithReply() {
    var replyText by remember { mutableStateOf("") }

    Box(modifier = Modifier.padding(16.dp)) {
        NotificationReplyPill(
            sender = "WhatsApp",
            message = "Hey! Are you coming to the party tonight?",
            replyText = replyText,
            onReplyChange = { replyText = it },
            onSend = { replyText = "" },
            sendLabel = "Gönder"
        )
    }
}

