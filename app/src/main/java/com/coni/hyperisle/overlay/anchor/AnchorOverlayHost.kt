package com.coni.hyperisle.overlay.anchor

import com.coni.hyperisle.util.HiLog
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.coni.hyperisle.BuildConfig

// --- Animation Configuration ---
private const val ANIM_DURATION = 300
// Physics-based spring for expansion (overshoot)
private val EXPAND_ANIM_SPEC = spring<Float>(
    dampingRatio = 0.65f, // Medium bounce for overshoot
    stiffness = 600f      // Tuned for approx 300ms settling
)
// Physics-based spring for shrinking (anticipate-like feel via high stiffness/no bounce)
// Note: True "anticipate" requires a custom easing curve with tween, but user asked for physics-based.
// A slightly under-damped spring can also simulate reaction.
// However, for "shrink", we usually want it snappy.
private val SHRINK_ANIM_SPEC = spring<Float>(
    dampingRatio = 1f,    // Critical damping (no bounce)
    stiffness = 600f
)

/**
 * Host composable for anchor-based overlay rendering.
 * 
 * Manages the anchor pill display and transitions between modes:
 * - ANCHOR_IDLE: Minimal anchor pill
 * - CALL_ACTIVE: Call info in anchor
 * - NAV_ACTIVE: Navigation info in anchor
 * - NOTIF_EXPANDED: Full notification (expands from anchor, shrinks back to anchor)
 */
@Composable
fun AnchorOverlayHost(
    anchorCoordinator: AnchorCoordinator,
    expandedContent: @Composable (() -> Unit)? = null,
    onAnchorTap: () -> Unit = {},
    onAnchorLongPress: () -> Unit = {},
    debugRid: Int = 0
) {
    val anchorState by anchorCoordinator.anchorState.collectAsState()
    val cutoutInfo by anchorCoordinator.cutoutInfo.collectAsState()

    val effectiveCutoutInfo = cutoutInfo ?: CutoutInfo.default(1080)
    val isExpanded = anchorState.mode == IslandMode.NOTIF_EXPANDED

    if (BuildConfig.DEBUG) {
        LaunchedEffect(anchorState.mode) {
            HiLog.d("HyperIsleAnchor",
                "RID=$debugRid EVT=ANCHOR_HOST_RENDER mode=${anchorState.mode} isExpanded=$isExpanded"
            )
        }
    }

    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 0.dp), // Zero vertical padding to overlap camera
        contentAlignment = Alignment.TopCenter
    ) {
        // Anchor Pill (visible when NOT expanded)
        AnimatedVisibility(
            visible = !isExpanded,
            enter = scaleIn(
                initialScale = 0.4f,
                animationSpec = SHRINK_ANIM_SPEC, // Re-appearing uses shrink spec (reverse of expand)
                transformOrigin = TransformOrigin(0.5f, 0f)
            ) + fadeIn(animationSpec = tween(ANIM_DURATION)),
            exit = scaleOut(
                targetScale = 0.4f,
                animationSpec = EXPAND_ANIM_SPEC, // Disappearing uses expand spec (expands out?)
                // Wait, if it's expanding TO content, the anchor should fade out/scale up?
                // Or simply disappear. 
                // Usually: Anchor scales UP to become Content.
                // Content scales DOWN to become Anchor.
                // So Anchor Exit should match Content Enter?
                // Content Enter is ScaleIn(0.5 -> 1).
                // Anchor Exit should be ScaleOut(1 -> ?).
                // Actually, standard cross-fade with scale is easiest.
                transformOrigin = TransformOrigin(0.5f, 0f)
            ) + fadeOut(animationSpec = tween(150))
        ) {
            when (anchorState.mode) {
                IslandMode.ANCHOR_IDLE -> {
                    IdleAnchorPill(
                        cutoutInfo = effectiveCutoutInfo,
                        onTap = onAnchorTap,
                        debugRid = debugRid
                    )
                }
                IslandMode.CALL_ACTIVE -> {
                    anchorState.callState?.let { callState ->
                        CallAnchorPill(
                            callState = callState,
                            cutoutInfo = effectiveCutoutInfo,
                            onTap = onAnchorTap,
                            onLongPress = onAnchorLongPress,
                            debugRid = debugRid
                        )
                    }
                }
                IslandMode.NAV_ACTIVE -> {
                    anchorState.navState?.let { navState ->
                        NavAnchorPill(
                            navState = navState,
                            cutoutInfo = effectiveCutoutInfo,
                            onTap = onAnchorTap,
                            onLongPress = onAnchorLongPress,
                            debugRid = debugRid
                        )
                    }
                }
                IslandMode.NOTIF_EXPANDED -> {
                }
            }
        }

        // Expanded Content (visible when EXPANDED)
        AnimatedVisibility(
            visible = isExpanded && expandedContent != null,
            enter = scaleIn(
                initialScale = 0.8f,
                animationSpec = spring(
                    dampingRatio = 0.7f,
                    stiffness = Spring.StiffnessLow
                ),
                transformOrigin = TransformOrigin(0.5f, 0f)
            ) + fadeIn(animationSpec = tween(200)),
            exit = scaleOut(
                targetScale = 0.8f,
                animationSpec = spring(
                    dampingRatio = 0.7f,
                    stiffness = Spring.StiffnessLow
                ),
                transformOrigin = TransformOrigin(0.5f, 0f)
            ) + fadeOut(animationSpec = tween(200))
        ) {
            // Add top padding to lower the expanded notification as requested
            Box(modifier = Modifier.padding(top = 18.dp)) {
                expandedContent?.invoke()
            }
        }
    }
}

/**
 * Notification content that expands from anchor and shrinks back.
 * Used within AnchorOverlayHost when mode is NOTIF_EXPANDED.
 */
@Composable
fun ExpandedNotificationContent(
    content: @Composable () -> Unit,
    onShrinkComplete: () -> Unit = {},
    debugRid: Int = 0
) {
    var isVisible by remember { mutableStateOf(false) }

    val animatedScale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.5f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "notif_expand_scale"
    )

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "notif_expand_alpha"
    )

    LaunchedEffect(Unit) {
        isVisible = true
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
                alpha = animatedAlpha
                transformOrigin = TransformOrigin(0.5f, 0f)
            }
    ) {
        content()
    }
}