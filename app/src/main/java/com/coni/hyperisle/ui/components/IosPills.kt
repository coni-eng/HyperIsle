package com.coni.hyperisle.ui.components

import android.graphics.Bitmap
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS-style pill container with rounded corners and semi-transparent black background.
 * Used as the base container for all pill-style overlays.
 */
@Composable
fun PillContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(50.dp)),
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
    onAccept: () -> Unit
) {
    PillContainer {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Avatar
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3A3A3C)),
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
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title,
                    color = Color(0xFF8E8E93),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
    onClick: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    PillContainer(
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Avatar with indicator dot
            Box(
                modifier = Modifier.size(44.dp)
            ) {
                // Avatar circle
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3A3A3C)),
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
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34C759)) // iOS green
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Center: Sender, time, and message
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                // Top row: Sender + Time
                Row(
                    modifier = Modifier.fillMaxWidth(),
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
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timeLabel,
                        color = Color(0xFF8E8E93),
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }

                // Message preview
                Text(
                    text = message,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (onDismiss != null) {
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
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
