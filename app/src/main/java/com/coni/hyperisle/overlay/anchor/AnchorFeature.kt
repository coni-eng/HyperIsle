package com.coni.hyperisle.overlay.anchor

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import com.coni.hyperisle.overlay.engine.ActiveIsland
import com.coni.hyperisle.overlay.engine.IslandActions
import com.coni.hyperisle.overlay.engine.IslandEvent
import com.coni.hyperisle.overlay.engine.IslandPolicy
import com.coni.hyperisle.overlay.engine.IslandRoute
import com.coni.hyperisle.overlay.features.FeatureUiState
import com.coni.hyperisle.overlay.features.IslandFeature
import com.coni.hyperisle.util.HiLog



/**
 * Feature for anchor-based island display.
 * 
 * This feature handles the anchor pill that wraps around the camera cutout.
 * It manages transitions between:
 * - ANCHOR_IDLE: Always visible idle state
 * - CALL_ACTIVE: Call info in anchor format
 * - NAV_ACTIVE: Navigation info in anchor format
 * - NOTIF_EXPANDED: Expanded notification (animates from anchor)
 */
class AnchorFeature(
    private val anchorCoordinator: AnchorCoordinator
) : IslandFeature {
    
    override val id: String = "anchor"

    override fun canHandle(event: IslandEvent): Boolean {
        return false
    }

    override fun reduce(prev: Any?, event: IslandEvent, nowMs: Long): Any? {
        return null
    }

    override fun priority(state: Any?): Int {
        return when (anchorCoordinator.getCurrentMode()) {
            IslandMode.CALL_ACTIVE -> ActiveIsland.PRIORITY_ONGOING_CALL
            IslandMode.NAV_ACTIVE -> ActiveIsland.PRIORITY_NAVIGATION_ACTIVE
            IslandMode.NAV_EXPANDED -> ActiveIsland.PRIORITY_NAVIGATION_ACTIVE
            IslandMode.NOTIF_EXPANDED -> ActiveIsland.PRIORITY_NOTIFICATION_IMPORTANT
            IslandMode.DOWNLOAD_ACTIVE -> ActiveIsland.PRIORITY_FALLBACK
            IslandMode.ANCHOR_IDLE -> 0
        }
    }

    override fun route(state: Any?): IslandRoute = IslandRoute.APP_OVERLAY

    override fun policy(state: Any?): IslandPolicy {
        return when (anchorCoordinator.getCurrentMode()) {
            IslandMode.CALL_ACTIVE -> IslandPolicy.ONGOING_CALL
            IslandMode.NAV_ACTIVE -> IslandPolicy.NAVIGATION
            IslandMode.NAV_EXPANDED -> IslandPolicy.NAVIGATION
            IslandMode.NOTIF_EXPANDED -> IslandPolicy.NOTIFICATION
            IslandMode.DOWNLOAD_ACTIVE -> IslandPolicy(
                minVisibleMs = 0L,
                collapseAfterMs = null,
                dismissAfterCollapseMs = null,
                sticky = true,
                dismissible = false,
                allowPassThrough = true
            )
            IslandMode.ANCHOR_IDLE -> IslandPolicy(
                minVisibleMs = 0L,
                collapseAfterMs = null,
                dismissAfterCollapseMs = null,
                sticky = true,
                dismissible = false,
                allowPassThrough = true
            )
        }
    }

    @Composable
    override fun Render(
        state: Any?,
        uiState: FeatureUiState,
        actions: IslandActions
    ) {
        val anchorState by anchorCoordinator.anchorState.collectAsState()
        val cutoutInfo by anchorCoordinator.cutoutInfo.collectAsState()
        val rid = uiState.debugRid

        val effectiveCutoutInfo = cutoutInfo ?: CutoutInfo.default(1080)

        if (BuildConfig.DEBUG) {
            LaunchedEffect(anchorState.mode) {
                HiLog.d(HiLog.TAG_ISLAND,
                    "EVT=ANCHOR_FEATURE_RENDER mode=${anchorState.mode} rid=$rid"
                )
            }
        }

        AnchorPillWithAnimation(
            anchorState = anchorState,
            cutoutInfo = effectiveCutoutInfo,
            onTap = {
                when (anchorState.mode) {
                    IslandMode.CALL_ACTIVE -> {
                        anchorState.callState?.let { _ ->
                            actions.showInCallScreen()
                            actions.hapticSuccess()
                        }
                    }
                    IslandMode.NAV_ACTIVE -> {
                        anchorState.navState?.let { nav ->
                            actions.openApp(nav.packageName)
                            actions.hapticSuccess()
                        }
                    }
                    IslandMode.NOTIF_EXPANDED, IslandMode.NAV_EXPANDED -> {
                        // Handled by expanded content or dismiss
                    }
                    IslandMode.DOWNLOAD_ACTIVE -> {
                        actions.hapticShown()
                    }
                    IslandMode.ANCHOR_IDLE -> {
                        // Optional: Open settings or do nothing
                        actions.hapticShown()
                    }
                }
            },
            onLongPress = {
                when (anchorState.mode) {
                    IslandMode.CALL_ACTIVE -> {
                        // Future: Show mini call controls
                        actions.hapticSuccess()
                    }
                    IslandMode.NAV_ACTIVE -> {
                        // Future: Show mini nav controls
                        actions.hapticSuccess()
                    }
                    else -> {}
                }
            },
            debugRid = rid
        )
    }
}

/**
 * Anchor pill with expand/shrink animation.
 */
@Composable
fun AnchorPillWithAnimation(
    anchorState: AnchorState,
    cutoutInfo: CutoutInfo,
    onTap: () -> Unit = {},
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
    debugRid: Int = 0
) {
    var isVisible by remember { mutableStateOf(false) }

    val animatedScale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.5f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "anchor_scale"
    )

    val animatedAlpha by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "anchor_alpha"
    )

    LaunchedEffect(Unit) {
        isVisible = true
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
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
            when (anchorState.mode) {
                IslandMode.ANCHOR_IDLE -> {
                    IdleAnchorPill(
                        cutoutInfo = cutoutInfo,
                        onTap = onTap,
                        debugRid = debugRid
                    )
                }
                IslandMode.CALL_ACTIVE -> {
                    anchorState.callState?.let { callState ->
                        CallAnchorPill(
                            callState = callState,
                            cutoutInfo = cutoutInfo,
                            onTap = onTap,
                            onLongPress = onLongPress,
                            debugRid = debugRid
                        )
                    } ?: IdleAnchorPill(
                        cutoutInfo = cutoutInfo,
                        onTap = onTap,
                        debugRid = debugRid
                    )
                }
                IslandMode.NAV_ACTIVE -> {
                    anchorState.navState?.let { navState ->
                        NavAnchorPill(
                            navState = navState,
                            cutoutInfo = cutoutInfo,
                            onTap = onTap,
                            onLongPress = onLongPress,
                            debugRid = debugRid
                        )
                    } ?: IdleAnchorPill(
                        cutoutInfo = cutoutInfo,
                        onTap = onTap,
                        debugRid = debugRid
                    )
                }
                IslandMode.DOWNLOAD_ACTIVE -> {
                    AnchorPill(
                        state = anchorState,
                        cutoutInfo = cutoutInfo,
                        onTap = onTap,
                        onLongPress = onLongPress,
                        debugRid = debugRid
                    )
                }
                IslandMode.NOTIF_EXPANDED, IslandMode.NAV_EXPANDED -> {
                }
            }
        }
    }
}
