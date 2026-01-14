package com.coni.hyperisle.overlay.host

import android.content.Context
import androidx.compose.runtime.Composable
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.overlay.OverlayWindowController
import com.coni.hyperisle.overlay.engine.ActiveIsland
import com.coni.hyperisle.overlay.engine.IslandPolicy
import com.coni.hyperisle.overlay.engine.IslandRoute
import com.coni.hyperisle.util.HiLog



/**
 * Host controller that wraps OverlayWindowController.
 * Provides a simplified API for the coordinator to manage the single overlay window.
 * 
 * CRITICAL: Only ONE overlay window is ever open. This controller ensures that.
 * All window flag management (touch/pass-through/measure) is encapsulated here.
 * Features do NOT have access to WindowManager.
 */
class OverlayHostController(
    context: Context
) {
    companion object {
        private const val TAG = "OverlayHostController"
    }

    private val windowController = OverlayWindowController(context)
    
    // Current state tracking
    private var currentIsland: ActiveIsland? = null
    private var isHardHidden: Boolean = false
    private var hardHideReason: String? = null

    /**
     * Check if overlay permission is granted.
     */
    fun hasOverlayPermission(): Boolean = windowController.hasOverlayPermission()

    /**
     * Check if overlay is currently showing.
     */
    fun isShowing(): Boolean = windowController.isShowing() && !isHardHidden

    /**
     * Show or update the overlay with the given island and content.
     */
    fun show(
        island: ActiveIsland,
        content: @Composable () -> Unit
    ) {
        if (!hasOverlayPermission()) {
            if (BuildConfig.DEBUG) {
                HiLog.w(HiLog.TAG_ISLAND, "EVT=HOST_SHOW_BLOCKED reason=NO_PERMISSION")
            }
            return
        }

        // Clear hard-hide when showing new content
        if (isHardHidden) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=HOST_HARD_HIDE_CLEAR prevReason=$hardHideReason")
            }
            isHardHidden = false
            hardHideReason = null
        }

        val isUpdate = currentIsland?.notificationKey == island.notificationKey
        val rid = island.notificationKey.hashCode()

        // Determine interactivity from policy
        val interactive = !island.policy.allowPassThrough || island.policy.isModal || island.policy.needsFocus

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=HOST_SHOW rid=$rid feature=${island.featureId} route=${island.route} interactive=$interactive isUpdate=$isUpdate")
        }

        currentIsland = island

        // Route check - only APP_OVERLAY renders via this controller
        if (!island.route.isOverlay()) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=HOST_ROUTE_SKIP rid=$rid route=${island.route} reason=NOT_APP_OVERLAY")
            }
            return
        }

        windowController.showOverlay(
            event = createLegacyEvent(island),
            interactive = interactive,
            content = content
        )

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=HOST_MEASURE rid=$rid w=PENDING h=PENDING")
        }
    }

    /**
     * Update window properties without recreating the overlay.
     */
    fun updateProperties(
        island: ActiveIsland
    ) {
        val rid = island.notificationKey.hashCode()

        // Update focusability based on policy
        val needsFocus = island.policy.needsFocus
        windowController.setFocusable(
            isFocusable = needsFocus,
            reason = if (needsFocus) "REPLY_OPEN" else "REPLY_CLOSE",
            rid = rid
        )

        // Request layout update
        val mode = when {
            island.isReplying -> "REPLY"
            island.isExpanded -> "EXPANDED"
            island.isCollapsed -> "COLLAPSED"
            else -> "NORMAL"
        }
        windowController.requestLayoutUpdate(mode, rid)

        currentIsland = island
    }

    /**
     * Dismiss the overlay normally.
     */
    fun dismiss(reason: String) {
        val rid = currentIsland?.notificationKey?.hashCode() ?: 0
        
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=HOST_DISMISS rid=$rid reason=$reason")
        }

        windowController.removeOverlay()
        currentIsland = null
        isHardHidden = false
        hardHideReason = null
    }

    /**
     * Force-hide the overlay (e.g., when dialer is foreground).
     * Window is removed but state is preserved for potential restore.
     */
    fun hardHide(reason: String) {
        val rid = currentIsland?.notificationKey?.hashCode() ?: 0

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=HOST_HARD_HIDE rid=$rid reason=$reason")
        }

        isHardHidden = true
        hardHideReason = reason
        windowController.forceDismissOverlay(reason)
    }

    /**
     * Force dismiss with guaranteed cleanup (for service destroyed scenarios).
     */
    fun forceDismiss(reason: String) {
        val rid = currentIsland?.notificationKey?.hashCode() ?: 0

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=HOST_FORCE_DISMISS rid=$rid reason=$reason")
        }

        windowController.forceDismissOverlay(reason)
        currentIsland = null
        isHardHidden = false
        hardHideReason = null
    }

    /**
     * Get the current active island.
     */
    fun getCurrentIsland(): ActiveIsland? = currentIsland

    /**
     * Check if overlay is hard-hidden.
     */
    fun isHardHidden(): Boolean = isHardHidden

    /**
     * Create legacy OverlayEvent for compatibility with existing OverlayWindowController.
     * This bridges the new architecture to the existing window management code.
     */
    private fun createLegacyEvent(island: ActiveIsland): com.coni.hyperisle.overlay.OverlayEvent {
        // Create a minimal event for tracking purposes
        // The actual content is rendered via the composable parameter
        return when (island.featureId) {
            "call" -> com.coni.hyperisle.overlay.OverlayEvent.CallEvent(
                com.coni.hyperisle.overlay.IosCallOverlayModel(
                    title = "",
                    callerName = "",
                    packageName = island.packageName,
                    notificationKey = island.notificationKey
                )
            )
            "notification" -> com.coni.hyperisle.overlay.OverlayEvent.NotificationEvent(
                com.coni.hyperisle.overlay.IosNotificationOverlayModel(
                    sender = "",
                    timeLabel = "",
                    message = "",
                    packageName = island.packageName,
                    notificationKey = island.notificationKey
                )
            )
            "timer" -> com.coni.hyperisle.overlay.OverlayEvent.TimerEvent(
                com.coni.hyperisle.overlay.TimerOverlayModel(
                    label = "",
                    baseTimeMs = 0L,
                    isCountdown = false,
                    packageName = island.packageName,
                    notificationKey = island.notificationKey
                )
            )
            "navigation" -> com.coni.hyperisle.overlay.OverlayEvent.NavigationEvent(
                com.coni.hyperisle.overlay.NavigationOverlayModel(
                    instruction = "",
                    distance = "",
                    eta = "",
                    packageName = island.packageName,
                    notificationKey = island.notificationKey
                )
            )
            "media" -> com.coni.hyperisle.overlay.OverlayEvent.MediaEvent(
                com.coni.hyperisle.overlay.MediaOverlayModel(
                    title = "",
                    subtitle = "",
                    packageName = island.packageName,
                    notificationKey = island.notificationKey
                )
            )
            else -> com.coni.hyperisle.overlay.OverlayEvent.NotificationEvent(
                com.coni.hyperisle.overlay.IosNotificationOverlayModel(
                    sender = "",
                    timeLabel = "",
                    message = "",
                    packageName = island.packageName,
                    notificationKey = island.notificationKey
                )
            )
        }
    }
}
