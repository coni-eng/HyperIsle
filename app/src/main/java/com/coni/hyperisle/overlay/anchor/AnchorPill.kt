package com.coni.hyperisle.overlay.anchor

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.util.HiLog



private val PillBackgroundColor = Color(0xE6000000)
private val ActiveGreenColor = Color(0xFF34C759)

/**
 * Anchor pill composable that wraps around the camera cutout.
 * 
 * Layout: [LeftSlot] [CutoutGap] [RightSlot]
 * The CutoutGap is always empty to avoid covering the camera.
 */
@Composable
fun AnchorPill(
    state: AnchorState,
    cutoutInfo: CutoutInfo,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
    debugRid: Int = 0
) {
    val density = LocalDensity.current
    
    // Reduced padding and min width for a more compact look as requested
    val cutoutGapWidth = with(density) { cutoutInfo.width.toDp() + 12.dp }
    val slotMinWidth = 48.dp
    val slotMaxWidth = 120.dp
    val pillHeight = 36.dp
    val pillPadding = 4.dp

    if (BuildConfig.DEBUG) {
        LaunchedEffect(state.mode, cutoutInfo) {
            val leftContent = when (state.leftSlot) {
                is SlotContent.Text -> "text:${(state.leftSlot as SlotContent.Text).text.take(10)}"
                is SlotContent.IconOnly -> "icon"
                is SlotContent.WaveBar -> "wavebar"
                is SlotContent.Empty, null -> "empty"
            }
            val rightContent = when (state.rightSlot) {
                is SlotContent.Text -> "text:${(state.rightSlot as SlotContent.Text).text.take(10)}"
                is SlotContent.IconOnly -> "icon"
                is SlotContent.WaveBar -> "wavebar"
                is SlotContent.Empty, null -> "empty"
            }
            val pillW = cutoutGapWidth.value.toInt() + slotMinWidth.value.toInt() * 2
            HiLog.d(HiLog.TAG_ISLAND,
                "EVT=ANCHOR_RENDER mode=${state.mode} left=$leftContent right=$rightContent cutoutW=${cutoutInfo.width} pillW=$pillW"
            )
        }
    }

    Surface(
        modifier = modifier
            .shadow(elevation = 8.dp, shape = RoundedCornerShape(pillHeight / 2))
            .clickable { onTap() },
        shape = RoundedCornerShape(pillHeight / 2),
        color = PillBackgroundColor
    ) {
        Row(
            modifier = Modifier
                .height(pillHeight)
                .padding(horizontal = pillPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnchorSlot(
                content = state.leftSlot,
                minWidth = slotMinWidth,
                maxWidth = slotMaxWidth,
                alignment = Alignment.CenterEnd,
                debugRid = debugRid,
                slotName = "left"
            )

            Spacer(
                modifier = Modifier.width(cutoutGapWidth)
            )

            AnchorSlot(
                content = state.rightSlot,
                minWidth = slotMinWidth,
                maxWidth = slotMaxWidth,
                alignment = Alignment.CenterStart,
                debugRid = debugRid,
                slotName = "right"
            )
        }
    }
}

/**
 * Slot content renderer for anchor pill.
 */
@Composable
private fun AnchorSlot(
    content: SlotContent?,
    minWidth: Dp,
    maxWidth: Dp,
    alignment: Alignment,
    debugRid: Int,
    slotName: String
) {
    Box(
        modifier = Modifier
            .widthIn(min = minWidth, max = maxWidth)
            .height(28.dp),
        contentAlignment = alignment
    ) {
        when (content) {
            is SlotContent.Text -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (alignment == Alignment.CenterEnd) 
                        Arrangement.End else Arrangement.Start,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    content.icon?.let { icon ->
                        SlotIconContent(icon, size = 14.dp)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = content.text,
                        color = content.textColor?.let { Color(it) } ?: Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            is SlotContent.IconOnly -> {
                Box(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SlotIconContent(content.icon, size = 16.dp)
                }
            }
            is SlotContent.WaveBar -> {
                WaveBarAnimation(
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            is SlotContent.Empty, null -> {
            }
        }
    }
}

/**
 * Icon content renderer.
 */
@Composable
private fun SlotIconContent(
    icon: SlotIcon,
    size: Dp
) {
    when (icon) {
        is SlotIcon.Resource -> {
            Icon(
                painter = androidx.compose.ui.res.painterResource(id = icon.resId),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size)
            )
        }
        is SlotIcon.Bitmap -> {
            androidx.compose.foundation.Image(
                bitmap = icon.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(size)
            )
        }
        is SlotIcon.Vector -> {
            Icon(
                imageVector = icon.imageVector,
                contentDescription = icon.description,
                tint = Color.White,
                modifier = Modifier.size(size)
            )
        }
    }
}

/**
 * Wave bar animation for active call indicator.
 */
@Composable
private fun WaveBarAnimation(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "wave_bar")
    
    val bar1 by transition.animateFloat(
        initialValue = 6f,
        targetValue = 14f,
        animationSpec = infiniteRepeatable(
            animation = tween(400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )
    val bar2 by transition.animateFloat(
        initialValue = 10f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )
    val bar3 by transition.animateFloat(
        initialValue = 8f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(
            animation = tween(450),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WaveBar(height = bar1.dp, color = ActiveGreenColor)
        WaveBar(height = bar2.dp, color = ActiveGreenColor)
        WaveBar(height = bar3.dp, color = ActiveGreenColor)
    }
}

@Composable
private fun WaveBar(height: Dp, color: Color) {
    Box(
        modifier = Modifier
            .width(3.dp)
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(color)
    )
}

/**
 * Anchor pill for call active mode.
 */
@Composable
fun CallAnchorPill(
    callState: CallAnchorState,
    cutoutInfo: CutoutInfo,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
    debugRid: Int = 0
) {
    val leftSlot = if (callState.isActive) {
        SlotContent.WaveBar
    } else {
        SlotContent.IconOnly(SlotIcon.Vector(Icons.Default.Phone, "Call"))
    }

    val rightSlot = SlotContent.Text(
        text = callState.durationText.ifEmpty { "00:00" },
        textColor = 0xFF34C759.toInt()
    )

    val anchorState = AnchorState(
        mode = IslandMode.CALL_ACTIVE,
        leftSlot = leftSlot,
        rightSlot = rightSlot,
        callState = callState,
        cutoutRect = cutoutInfo.rect
    )

    AnchorPill(
        state = anchorState,
        cutoutInfo = cutoutInfo,
        onTap = onTap,
        onLongPress = onLongPress,
        modifier = modifier,
        debugRid = debugRid
    )
}

/**
 * Anchor pill for navigation active mode.
 */
@Composable
fun NavAnchorPill(
    navState: NavAnchorState,
    cutoutInfo: CutoutInfo,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
    debugRid: Int = 0
) {
    val leftText = when (navState.leftInfoType) {
        NavInfoType.INSTRUCTION -> navState.instruction
        NavInfoType.DISTANCE -> navState.distance
        NavInfoType.ETA -> navState.eta
        NavInfoType.REMAINING_TIME -> navState.remainingTime
        NavInfoType.SPEED -> ""
        NavInfoType.NONE -> ""
    }

    val rightText = when (navState.rightInfoType) {
        NavInfoType.INSTRUCTION -> navState.instruction
        NavInfoType.DISTANCE -> navState.distance
        NavInfoType.ETA -> navState.eta
        NavInfoType.REMAINING_TIME -> navState.remainingTime
        NavInfoType.SPEED -> ""
        NavInfoType.NONE -> ""
    }

    val leftSlot = if (leftText.isNotEmpty()) {
        SlotContent.Text(
            text = leftText,
            icon = SlotIcon.Vector(Icons.Default.Navigation, "Navigation")
        )
    } else {
        SlotContent.Empty
    }

    val rightSlot = if (rightText.isNotEmpty()) {
        SlotContent.Text(text = rightText)
    } else {
        SlotContent.Empty
    }

    val anchorState = AnchorState(
        mode = IslandMode.NAV_ACTIVE,
        leftSlot = leftSlot,
        rightSlot = rightSlot,
        navState = navState,
        cutoutRect = cutoutInfo.rect
    )

    AnchorPill(
        state = anchorState,
        cutoutInfo = cutoutInfo,
        onTap = onTap,
        onLongPress = onLongPress,
        modifier = modifier,
        debugRid = debugRid
    )
}

/**
 * Idle anchor pill with minimal display.
 */
@Composable
fun IdleAnchorPill(
    cutoutInfo: CutoutInfo,
    leftText: String = "",
    rightText: String = "",
    onTap: () -> Unit = {},
    modifier: Modifier = Modifier,
    debugRid: Int = 0
) {
    val leftSlot = if (leftText.isNotEmpty()) {
        SlotContent.Text(text = leftText)
    } else {
        SlotContent.Empty
    }

    val rightSlot = if (rightText.isNotEmpty()) {
        SlotContent.Text(text = rightText)
    } else {
        SlotContent.Empty
    }

    val anchorState = AnchorState(
        mode = IslandMode.ANCHOR_IDLE,
        leftSlot = leftSlot,
        rightSlot = rightSlot,
        cutoutRect = cutoutInfo.rect
    )

    AnchorPill(
        state = anchorState,
        cutoutInfo = cutoutInfo,
        onTap = onTap,
        modifier = modifier,
        debugRid = debugRid
    )
}
