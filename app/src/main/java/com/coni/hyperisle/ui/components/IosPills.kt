package com.coni.hyperisle.ui.components

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coni.hyperisle.BuildConfig
import kotlin.math.roundToInt

private data class LayoutSnapshot(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

@Composable
private fun debugLayoutModifier(rid: Int?, element: String): Modifier {
    if (!BuildConfig.DEBUG || rid == null) return Modifier
    var lastSnapshot by remember { mutableStateOf<LayoutSnapshot?>(null) }
    return Modifier.onGloballyPositioned { coords ->
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
    debugRid: Int? = null,
    debugName: String = "pill",
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(50.dp))
            .then(debugLayoutModifier(debugRid, "${debugName}_root")),
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
                .then(debugLayoutModifier(debugRid, "call_row")),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A3A3C))
                    .then(debugLayoutModifier(debugRid, "call_avatar")),
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
                    .then(debugLayoutModifier(debugRid, "call_text_column")),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    color = Color(0xFF8E8E93),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = debugLayoutModifier(debugRid, "call_title")
                )
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = debugLayoutModifier(debugRid, "call_name")
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Right: Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Decline button (red)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                        .then(debugLayoutModifier(debugRid, "call_decline_btn"))
                        .clickable { onDecline() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "Decline call",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Accept button (green)
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF43A047))
                        .then(debugLayoutModifier(debugRid, "call_accept_btn"))
                        .clickable { onAccept() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Accept call",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/**
 * iOS-style notification pill with avatar (+ indicator dot), sender info, and message preview.
 * 
 * @param sender Bold white text showing sender/app name
 * @param timeLabel Grey text showing time (e.g., "now", "2m ago")
 * @param message Single-line message preview with reduced opacity
 * @param avatarBitmap Optional avatar/app icon bitmap
 * @param onClick Optional callback when pill is tapped
 * @param onDismiss Optional callback when close button is tapped
 */
@Composable
fun NotificationPill(
    sender: String,
    timeLabel: String,
    message: String,
    avatarBitmap: Bitmap? = null,
    replyLabel: String? = null,
    onReply: (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    debugRid: Int? = null
) {
    if (BuildConfig.DEBUG && debugRid != null) {
        val hasAvatar = avatarBitmap != null
        val hasDismiss = onDismiss != null
        val hasClick = onClick != null
        val hasReply = onReply != null && !replyLabel.isNullOrBlank()
        LaunchedEffect(sender, timeLabel, message, hasAvatar, hasDismiss, hasClick, hasReply) {
            Log.d(
                "HyperIsleIsland",
                "RID=$debugRid EVT=UI_CONTENT type=NOTIFICATION senderLen=${sender.length} timeLen=${timeLabel.length} messageLen=${message.length} hasAvatar=$hasAvatar hasDismiss=$hasDismiss hasClick=$hasClick hasReply=$hasReply"
            )
        }
    }
    PillContainer(
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier,
        debugRid = debugRid,
        debugName = "notif"
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(debugLayoutModifier(debugRid, "notif_row")),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Avatar with indicator dot
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .then(debugLayoutModifier(debugRid, "notif_avatar_stack"))
            ) {
                // Avatar circle
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3A3A3C))
                        .then(debugLayoutModifier(debugRid, "notif_avatar")),
                    contentAlignment = Alignment.Center
                ) {
                    if (avatarBitmap != null) {
                        Image(
                            bitmap = avatarBitmap.asImageBitmap(),
                            contentDescription = "App icon",
                            modifier = Modifier.size(44.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Default icon",
                            tint = Color(0xFF8E8E93),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Green indicator dot (bottom-left)
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .offset(x = 0.dp, y = 32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1B1B1B)) // Border color (near black)
                        .padding(2.dp)
                        .then(debugLayoutModifier(debugRid, "notif_indicator_border"))
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34C759)) // iOS green
                            .then(debugLayoutModifier(debugRid, "notif_indicator_dot"))
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Center: Sender, time, and message
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(debugLayoutModifier(debugRid, "notif_text_column")),
                verticalArrangement = Arrangement.Center
            ) {
                // Top row: Sender + Time
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(debugLayoutModifier(debugRid, "notif_header_row")),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = sender,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(debugLayoutModifier(debugRid, "notif_sender"))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timeLabel,
                        color = Color(0xFF8E8E93),
                        fontSize = 12.sp,
                        maxLines = 1,
                        modifier = debugLayoutModifier(debugRid, "notif_time")
                    )
                }

                // Message preview
                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = debugLayoutModifier(debugRid, "notif_message")
                )
            }

            if (!replyLabel.isNullOrBlank() && onReply != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2C2C2E))
                        .then(debugLayoutModifier(debugRid, "notif_reply_btn"))
                        .clickable { onReply() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = replyLabel,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (onDismiss != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .then(debugLayoutModifier(debugRid, "notif_dismiss_btn"))
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss notification",
                        tint = Color(0xFF8E8E93),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
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
                .then(debugLayoutModifier(debugRid, "notif_mini_row")),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .then(debugLayoutModifier(debugRid, "notif_mini_avatar_stack"))
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3A3A3C))
                        .then(debugLayoutModifier(debugRid, "notif_mini_avatar")),
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
                        .then(debugLayoutModifier(debugRid, "notif_mini_indicator_border"))
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34C759))
                            .then(debugLayoutModifier(debugRid, "notif_mini_indicator_dot"))
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
                    .then(debugLayoutModifier(debugRid, "notif_mini_sender"))
            )

            if (onDismiss != null) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .then(debugLayoutModifier(debugRid, "notif_mini_dismiss_btn"))
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
            timeLabel = "now",
            message = "Hey! Are you coming to the party tonight?",
            avatarBitmap = null,
            onClick = {},
            onDismiss = {}
        )
    }
}
