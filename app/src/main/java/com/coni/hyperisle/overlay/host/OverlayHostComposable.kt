package com.coni.hyperisle.overlay.host

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.overlay.engine.ActiveIsland
import com.coni.hyperisle.overlay.engine.IslandActions
import com.coni.hyperisle.overlay.features.FeatureUiState
import com.coni.hyperisle.overlay.features.IslandFeature
import com.coni.hyperisle.util.HiLog
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch



/**
 * Host composable that renders the active feature's UI.
 * This is the single rendering point for all island types.
 * 
 * Handles:
 * - Swipe-to-dismiss gesture
 * - Container tap/long-press
 * - Modal overlay (for reply mode)
 * - Touch pass-through management
 */
@Composable
fun OverlayHostComposable(
    island: ActiveIsland,
    feature: IslandFeature,
    actions: IslandActions,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onSwipeDismiss: () -> Unit
) {
    val rid = island.notificationKey.hashCode()
    val policy = island.policy
    val uiState = FeatureUiState.from(island)

    // Determine container mode
    val isModal = policy.isModal || island.isReplying

    if (isModal) {
        // Modal mode: full screen dim background to capture outside taps
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .pointerInput(Unit) {
                    // Capture taps on dim background to close reply
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            // Background tap closes reply
                            if (event.changes.any { it.pressed }) {
                                actions.exitReplyMode()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            SwipeDismissContainer(
                rid = rid,
                stateLabel = getStateLabel(island),
                onDismiss = onSwipeDismiss,
                onTap = onTap,
                onLongPress = onLongPress,
                isDismissible = policy.dismissible,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                feature.Render(
                    state = island.state,
                    uiState = uiState,
                    actions = actions
                )
            }
        }
    } else {
        // Normal mode: wrapContentSize allows touch pass-through outside pill area
        Box(
            modifier = Modifier
                .wrapContentSize(unbounded = true)
                .background(Color.Transparent),
            contentAlignment = Alignment.TopCenter
        ) {
            SwipeDismissContainer(
                rid = rid,
                stateLabel = getStateLabel(island),
                onDismiss = onSwipeDismiss,
                onTap = onTap,
                onLongPress = onLongPress,
                isDismissible = policy.dismissible,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                feature.Render(
                    state = island.state,
                    uiState = uiState,
                    actions = actions
                )
            }
        }
    }
}

/**
 * Container with swipe-to-dismiss, tap, and long-press gesture support.
 */
@Composable
fun SwipeDismissContainer(
    rid: Int,
    stateLabel: String,
    onDismiss: () -> Unit,
    onTap: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
    isDismissible: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var offsetY by remember { mutableFloatStateOf(0f) }
    val dismissThreshold = 100f

    LaunchedEffect(stateLabel) {
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "RID=$rid EVT=OVERLAY_LAYOUT state=$stateLabel")
        }
    }

    Box(
        modifier = modifier
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .pointerInput(isDismissible, onTap, onLongPress) {
                var startY = 0f
                var isDragging = false
                var downTime = 0L
                val longPressTimeout = 500L
                val tapTimeout = 200L

                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        startY = down.position.y
                        downTime = System.currentTimeMillis()
                        isDragging = false

                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break

                                if (!change.pressed) {
                                    // Pointer released
                                    val elapsed = System.currentTimeMillis() - downTime
                                    val totalDrag = abs(offsetY)

                                    when {
                                        // Swipe up dismiss
                                        isDismissible && offsetY < -dismissThreshold -> {
                                            if (BuildConfig.DEBUG) {
                                                HiLog.d(HiLog.TAG_ISLAND, "RID=$rid EVT=SWIPE_DISMISS offsetY=$offsetY")
                                            }
                                            onDismiss()
                                            offsetY = 0f
                                        }
                                        // Long press (if no significant drag)
                                        elapsed >= longPressTimeout && totalDrag < 30f && onLongPress != null -> {
                                            if (BuildConfig.DEBUG) {
                                                HiLog.d(HiLog.TAG_ISLAND, "RID=$rid EVT=LONG_PRESS elapsed=$elapsed")
                                            }
                                            onLongPress()
                                            offsetY = 0f
                                        }
                                        // Tap (quick release, no significant drag)
                                        elapsed < tapTimeout && totalDrag < 30f && onTap != null -> {
                                            if (BuildConfig.DEBUG) {
                                                HiLog.d(HiLog.TAG_ISLAND, "RID=$rid EVT=TAP elapsed=$elapsed")
                                            }
                                            onTap()
                                            offsetY = 0f
                                        }
                                        // Release without action - animate back
                                        else -> {
                                            coroutineScope.launch {
                                                animate(
                                                    initialValue = offsetY,
                                                    targetValue = 0f,
                                                    animationSpec = spring(
                                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                                        stiffness = Spring.StiffnessMedium
                                                    )
                                                ) { value, _ ->
                                                    offsetY = value
                                                }
                                            }
                                        }
                                    }
                                    break
                                }

                                // Track drag
                                val dragY = change.position.y - startY
                                if (abs(dragY) > 10f) {
                                    isDragging = true
                                }
                                
                                // Only allow upward drag for dismiss
                                if (isDismissible && dragY < 0) {
                                    offsetY = dragY * 0.5f // Resistance factor
                                    change.consume()
                                }
                            }
                        } catch (e: Exception) {
                            // Gesture cancelled
                            offsetY = 0f
                        }
                    }
                }
            },
        contentAlignment = Alignment.TopCenter
    ) {
        content()
    }
}

private fun getStateLabel(island: ActiveIsland): String {
    return when {
        island.isReplying -> "${island.featureId}_replying"
        island.isExpanded -> "${island.featureId}_expanded"
        island.isCollapsed -> "${island.featureId}_collapsed"
        else -> "${island.featureId}_normal"
    }
}
