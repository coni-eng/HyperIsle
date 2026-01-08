package com.coni.hyperisle.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS-like Modern Pill Island colors.
 * Dark surface with subtle transparency for the "frosted glass" effect.
 */
object ModernIslandColors {
    val pillBackground = Color(0xFF1C1C1E)
    val pillBackgroundExpanded = Color(0xFF2C2C2E)
    val textPrimary = Color.White
    val textSecondary = Color(0xFFAEAEB2)
    val acceptGreen = Color(0xFF34C759)
    val rejectRed = Color(0xFFFF3B30)
    val actionGray = Color(0xFF48484A)
    val timeLabel = Color(0xFF8E8E93)
}

/**
 * Data class for island action buttons.
 */
data class IslandAction(
    val icon: ImageVector,
    val contentDescription: String,
    val backgroundColor: Color = ModernIslandColors.actionGray,
    val iconTint: Color = Color.White,
    val onClick: () -> Unit
)

/**
 * Modern Pill Island Composable - iOS-like notification pill.
 * 
 * Features:
 * - Pill shape with soft shadow
 * - Left: app icon/avatar (circle)
 * - Center: title (1 line) + subtitle (1 line, smaller)
 * - Right: up to 2 icon buttons
 * - Supports collapsed/expanded states with smooth animation
 * 
 * @param title Main title text (1 line)
 * @param subtitle Secondary text (1 line, smaller)
 * @param appIcon Optional bitmap for app icon/avatar
 * @param appIconPlaceholder Placeholder icon if no bitmap
 * @param timeLabel Optional time label (e.g., "now", "1m")
 * @param actions List of action buttons (max 2)
 * @param isExpanded Whether the island is in expanded state
 * @param onExpandToggle Callback when user taps to expand/collapse
 * @param modifier Modifier for the composable
 */
@Composable
fun ModernPillIsland(
    title: String,
    subtitle: String,
    appIcon: Bitmap? = null,
    appIconPlaceholder: ImageVector = Icons.Default.Notifications,
    timeLabel: String? = null,
    actions: List<IslandAction> = emptyList(),
    isExpanded: Boolean = false,
    onExpandToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cornerRadius by animateDpAsState(
        targetValue = if (isExpanded) 28.dp else 24.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "cornerRadius"
    )
    
    val horizontalPadding by animateDpAsState(
        targetValue = if (isExpanded) 16.dp else 12.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "horizontalPadding"
    )
    
    val verticalPadding by animateDpAsState(
        targetValue = if (isExpanded) 14.dp else 10.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "verticalPadding"
    )

    val elevation by animateDpAsState(
        targetValue = if (isExpanded) 12.dp else 8.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "elevation"
    )

    Surface(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(cornerRadius),
                ambientColor = Color.Black.copy(alpha = 0.3f),
                spotColor = Color.Black.copy(alpha = 0.3f)
            )
            .clip(RoundedCornerShape(cornerRadius))
            .then(
                if (onExpandToggle != null) {
                    Modifier.clickable { onExpandToggle() }
                } else Modifier
            )
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            .semantics {
                contentDescription = "Notification from $title: $subtitle"
            },
        color = if (isExpanded) ModernIslandColors.pillBackgroundExpanded else ModernIslandColors.pillBackground,
        shape = RoundedCornerShape(cornerRadius)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: App Icon / Avatar
            IslandAppIcon(
                bitmap = appIcon,
                placeholder = appIconPlaceholder,
                size = if (isExpanded) 44.dp else 36.dp,
                contentDescription = "$title app icon"
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Center: Title + Subtitle
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        color = ModernIslandColors.textPrimary,
                        fontSize = if (isExpanded) 16.sp else 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    
                    // Time label (right of title)
                    if (timeLabel != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = timeLabel,
                            color = ModernIslandColors.timeLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
                
                if (subtitle.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = ModernIslandColors.textSecondary,
                        fontSize = if (isExpanded) 14.sp else 12.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            
            // Right: Action Buttons (max 2)
            AnimatedVisibility(
                visible = actions.isNotEmpty(),
                enter = fadeIn(tween(200)) + expandHorizontally(),
                exit = fadeOut(tween(200)) + shrinkHorizontally()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.width(8.dp))
                    actions.take(2).forEach { action ->
                        IslandActionButton(
                            icon = action.icon,
                            contentDescription = action.contentDescription,
                            backgroundColor = action.backgroundColor,
                            iconTint = action.iconTint,
                            size = if (isExpanded) 36.dp else 32.dp,
                            onClick = action.onClick
                        )
                    }
                }
            }
        }
    }
}

/**
 * Modern Call Island Composable - iOS-like incoming call UI.
 * 
 * Features:
 * - Pill shape with prominent accept/reject buttons
 * - Caller display (name/number)
 * - Green accept and red reject buttons with clear icons
 * - Does NOT log/store contact names (privacy-safe)
 * 
 * @param callerDisplay Display name or number (not stored)
 * @param callTypeLabel Label like "iPhone" or "Mobile"
 * @param callerIcon Optional bitmap for caller avatar
 * @param isExpanded Whether the island is in expanded state
 * @param onAccept Callback when accept button is pressed
 * @param onReject Callback when reject button is pressed
 * @param onExpandToggle Callback when user taps to expand/collapse
 * @param modifier Modifier for the composable
 */
@Composable
fun ModernCallIsland(
    callerDisplay: String,
    callTypeLabel: String = "",
    callerIcon: Bitmap? = null,
    isExpanded: Boolean = true,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onExpandToggle: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cornerRadius by animateDpAsState(
        targetValue = if (isExpanded) 32.dp else 24.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "cornerRadius"
    )

    val elevation by animateDpAsState(
        targetValue = if (isExpanded) 16.dp else 8.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "elevation"
    )

    Surface(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = RoundedCornerShape(cornerRadius),
                ambientColor = Color.Black.copy(alpha = 0.4f),
                spotColor = Color.Black.copy(alpha = 0.4f)
            )
            .clip(RoundedCornerShape(cornerRadius))
            .then(
                if (onExpandToggle != null) {
                    Modifier.clickable { onExpandToggle() }
                } else Modifier
            )
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            )
            .semantics {
                contentDescription = "Incoming call from $callerDisplay"
            },
        color = ModernIslandColors.pillBackground,
        shape = RoundedCornerShape(cornerRadius)
    ) {
        Row(
            modifier = Modifier
                .padding(
                    horizontal = if (isExpanded) 16.dp else 12.dp,
                    vertical = if (isExpanded) 12.dp else 8.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Caller Avatar
            IslandAppIcon(
                bitmap = callerIcon,
                placeholder = Icons.Default.Call,
                size = if (isExpanded) 48.dp else 36.dp,
                contentDescription = "Caller avatar"
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Center: Caller Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                if (callTypeLabel.isNotEmpty()) {
                    Text(
                        text = callTypeLabel,
                        color = ModernIslandColors.textSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = callerDisplay,
                    color = ModernIslandColors.textPrimary,
                    fontSize = if (isExpanded) 18.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Right: Accept/Reject Buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reject Button (Red)
                IslandActionButton(
                    icon = Icons.Default.CallEnd,
                    contentDescription = "Reject call",
                    backgroundColor = ModernIslandColors.rejectRed,
                    iconTint = Color.White,
                    size = if (isExpanded) 44.dp else 36.dp,
                    onClick = onReject
                )
                
                // Accept Button (Green)
                IslandActionButton(
                    icon = Icons.Default.Call,
                    contentDescription = "Accept call",
                    backgroundColor = ModernIslandColors.acceptGreen,
                    iconTint = Color.White,
                    size = if (isExpanded) 44.dp else 36.dp,
                    onClick = onAccept
                )
            }
        }
    }
}

/**
 * Circular app icon component for islands.
 */
@Composable
private fun IslandAppIcon(
    bitmap: Bitmap?,
    placeholder: ImageVector,
    size: Dp,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val animatedSize by animateDpAsState(
        targetValue = size,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "iconSize"
    )
    
    Box(
        modifier = modifier
            .size(animatedSize)
            .clip(CircleShape)
            .background(ModernIslandColors.actionGray),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier
                    .size(animatedSize)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = placeholder,
                contentDescription = contentDescription,
                tint = ModernIslandColors.textSecondary,
                modifier = Modifier.size(animatedSize * 0.5f)
            )
        }
    }
}

/**
 * Circular action button for islands.
 */
@Composable
private fun IslandActionButton(
    icon: ImageVector,
    contentDescription: String,
    backgroundColor: Color,
    iconTint: Color,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val animatedSize by animateDpAsState(
        targetValue = size,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "buttonSize"
    )
    
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(animatedSize)
            .clip(CircleShape)
            .background(backgroundColor)
            .semantics { this.contentDescription = contentDescription },
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = backgroundColor,
            contentColor = iconTint
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(animatedSize * 0.5f)
        )
    }
}

/**
 * Collapsed pill island - minimal state showing just icon and brief info.
 * Used when island is in collapsed/minimized state.
 */
@Composable
fun CollapsedPillIsland(
    appIcon: Bitmap? = null,
    appIconPlaceholder: ImageVector = Icons.Default.Notifications,
    indicatorColor: Color = ModernIslandColors.acceptGreen,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black.copy(alpha = 0.3f),
                spotColor = Color.Black.copy(alpha = 0.3f)
            )
            .clip(RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .semantics {
                contentDescription = "Collapsed notification, tap to expand"
            },
        color = ModernIslandColors.pillBackground,
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IslandAppIcon(
                bitmap = appIcon,
                placeholder = appIconPlaceholder,
                size = 28.dp,
                contentDescription = "App icon"
            )
            
            // Activity indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
        }
    }
}
