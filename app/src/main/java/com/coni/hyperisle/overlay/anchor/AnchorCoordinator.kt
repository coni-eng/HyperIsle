package com.coni.hyperisle.overlay.anchor

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Phone
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.overlay.engine.ActiveIsland
import com.coni.hyperisle.util.HiLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow



/**
 * Coordinator for anchor-based island display.
 * 
 * Manages the anchor state and mode transitions:
 * - ANCHOR_IDLE: Always visible, minimal display
 * - CALL_ACTIVE: Call info in anchor format
 * - NAV_ACTIVE: Navigation info in anchor format
 * - NOTIF_EXPANDED: Full notification display (expands from anchor)
 * 
 * Priority: CALL_ACTIVE > NOTIF_EXPANDED > NAV_ACTIVE > ANCHOR_IDLE
 */
class AnchorCoordinator(
    private val context: Context
) {
    companion object {
        private const val FEATURE_CALL = "call"
        private const val FEATURE_NAVIGATION = "navigation"
        private const val FEATURE_NOTIFICATION = "notification"
    }

    private val _anchorState = MutableStateFlow(AnchorState())
    val anchorState: StateFlow<AnchorState> = _anchorState.asStateFlow()

    private val _cutoutInfo = MutableStateFlow<CutoutInfo?>(null)
    val cutoutInfo: StateFlow<CutoutInfo?> = _cutoutInfo.asStateFlow()

    private var activeCallState: CallAnchorState? = null
    private var activeNavState: NavAnchorState? = null
    private var isNotifExpanded: Boolean = false
    private var isNavExpanded: Boolean = false
    private var expandedNotificationKey: String? = null

    init {
        refreshCutoutInfo()
    }

    /**
     * Refresh cutout information from system.
     */
    fun refreshCutoutInfo() {
        val info = CutoutHelper.getCutoutInfoOrDefault(context)
        _cutoutInfo.value = info

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=CUTOUT_REFRESH w=${info.width} h=${info.height} centerX=${info.centerX}")
        }
    }

    /**
     * Update call state for anchor display.
     */
    fun updateCallState(callState: CallAnchorState?) {
        val prevMode = _anchorState.value.mode
        activeCallState = callState

        if (BuildConfig.DEBUG) {
            val cutout = _cutoutInfo.value
            HiLog.d(HiLog.TAG_ISLAND,
                "EVT=CALL_STATE_UPDATE hasCall=${callState != null} isActive=${callState?.isActive} duration=${callState?.durationText}"
            )
        }

        updateAnchorMode()

        if (BuildConfig.DEBUG && prevMode != _anchorState.value.mode) {
            val cutout = _cutoutInfo.value
            val pillW = cutout?.width?.plus(120 + 16) ?: 0
            HiLog.d(HiLog.TAG_ISLAND, "EVT=MODE_SWITCH from=$prevMode to=${_anchorState.value.mode} reason=CALL_STATE cutoutW=${cutout?.width ?: 0} pillW=$pillW")
        }
    }

    /**
     * Update navigation state for anchor display.
     */
    fun updateNavState(navState: NavAnchorState?) {
        val prevMode = _anchorState.value.mode
        activeNavState = navState

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND,
                "EVT=NAV_STATE_UPDATE hasNav=${navState != null} instruction=${navState?.instruction?.take(20)}"
            )
        }

        updateAnchorMode()

        if (BuildConfig.DEBUG && prevMode != _anchorState.value.mode) {
            val cutout = _cutoutInfo.value
            val pillW = cutout?.width?.plus(120 + 16) ?: 0
            HiLog.d(HiLog.TAG_ISLAND, "EVT=MODE_SWITCH from=$prevMode to=${_anchorState.value.mode} reason=NAV_STATE cutoutW=${cutout?.width ?: 0} pillW=$pillW")
        }
    }

    /**
     * Expand navigation from anchor.
     */
    fun expandNavigation() {
        val prevMode = _anchorState.value.mode
        if (activeNavState == null) return

        isNavExpanded = true
        isNotifExpanded = false // Prioritize Nav expansion

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=NAV_EXPAND prevMode=$prevMode")
        }

        updateAnchorMode()

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=MODE_SWITCH from=$prevMode to=${_anchorState.value.mode} reason=NAV_EXPAND")
        }
    }

    /**
     * Expand notification from anchor.
     */
    fun expandNotification(notificationKey: String) {
        val prevMode = _anchorState.value.mode
        
        // v1.0.3 FIX: If already in a content mode (CALL/NAV), treat expansion as a new event
        // This ensures the host composable resets and measures correctly, preventing clipping
        if (prevMode == IslandMode.CALL_ACTIVE || prevMode == IslandMode.NAV_ACTIVE) {
            isNotifExpanded = false // Reset first
            // Emit a transient state update to force re-composition if needed
        }
        
        isNotifExpanded = true
        expandedNotificationKey = notificationKey

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=NOTIF_EXPAND key=${HiLog.hashKey(notificationKey)} prevMode=$prevMode")
        }

        _anchorState.value = _anchorState.value.copy(
            mode = IslandMode.NOTIF_EXPANDED,
            previousMode = prevMode,
            expandedNotificationKey = notificationKey,
            modeStartedAtMs = System.currentTimeMillis()
        )

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=MODE_SWITCH from=$prevMode to=NOTIF_EXPANDED reason=NOTIF_EXPAND")
        }
    }

    /**
     * Shrink notification back to anchor.
     * Called after cooldown or user dismiss.
     */
    fun shrinkToAnchor(reason: String) {
        val prevMode = _anchorState.value.mode
        
        if (isNavExpanded) {
            isNavExpanded = false
            updateAnchorMode()
            if (BuildConfig.DEBUG) {
                 HiLog.d(HiLog.TAG_ISLAND, "EVT=NAV_SHRINK reason=$reason")
            }
            return
        }

        if (prevMode != IslandMode.NOTIF_EXPANDED) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=SHRINK_SKIP reason=NOT_EXPANDED currentMode=$prevMode")
            }
            return
        }

        isNotifExpanded = false
        expandedNotificationKey = null

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=NOTIF_SHRINK reason=$reason")
        }

        updateAnchorMode()

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=MODE_SWITCH from=$prevMode to=${_anchorState.value.mode} reason=$reason")
        }
    }

    /**
     * Clear all state (service destroyed).
     */
    fun clearAll() {
        activeCallState = null
        activeNavState = null
        isNotifExpanded = false
        isNavExpanded = false
        expandedNotificationKey = null
        _anchorState.value = AnchorState()

        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=ANCHOR_CLEAR_ALL")
        }
    }

    /**
     * Update anchor mode based on current states.
     * Priority: CALL_ACTIVE > NAV_ACTIVE > ANCHOR_IDLE
     * (NOTIF_EXPANDED is handled separately via expand/shrink)
     */
    private fun updateAnchorMode() {
        if (isNotifExpanded) {
            return
        }
        
        if (isNavExpanded && activeNavState != null) {
            _anchorState.value = AnchorState(
                mode = IslandMode.NAV_EXPANDED,
                callState = activeCallState,
                navState = activeNavState,
                cutoutRect = _cutoutInfo.value?.rect,
                modeStartedAtMs = System.currentTimeMillis()
            )
            return
        }

        val newMode = when {
            activeCallState != null -> IslandMode.CALL_ACTIVE
            activeNavState != null -> IslandMode.NAV_ACTIVE
            else -> IslandMode.ANCHOR_IDLE
        }

        val (leftSlot, rightSlot) = when (newMode) {
            IslandMode.CALL_ACTIVE -> {
                val call = activeCallState!!
                val left = if (call.isActive) {
                    SlotContent.WaveBar
                } else {
                    SlotContent.IconOnly(SlotIcon.Vector(Icons.Default.Phone, "Call"))
                }
                val right = SlotContent.Text(
                    text = call.durationText.ifEmpty { "00:00" },
                    textColor = 0xFF34C759.toInt()
                )
                left to right
            }
            IslandMode.NAV_ACTIVE -> {
                val nav = activeNavState!!
                val leftText = when (nav.leftInfoType) {
                    NavInfoType.INSTRUCTION -> nav.instruction
                    NavInfoType.DISTANCE -> nav.distance
                    NavInfoType.ETA -> nav.eta
                    NavInfoType.REMAINING_TIME -> nav.remainingTime
                    else -> ""
                }
                val rightText = when (nav.rightInfoType) {
                    NavInfoType.INSTRUCTION -> nav.instruction
                    NavInfoType.DISTANCE -> nav.distance
                    NavInfoType.ETA -> nav.eta
                    NavInfoType.REMAINING_TIME -> nav.remainingTime
                    else -> ""
                }
                val left = if (leftText.isNotEmpty()) {
                    SlotContent.Text(
                        text = leftText,
                        icon = SlotIcon.Vector(Icons.Default.Navigation, "Navigation")
                    )
                } else {
                    SlotContent.Empty
                }
                val right = if (rightText.isNotEmpty()) {
                    SlotContent.Text(text = rightText)
                } else {
                    SlotContent.Empty
                }
                left to right
            }
            else -> SlotContent.Empty to SlotContent.Empty
        }

        _anchorState.value = AnchorState(
            mode = newMode,
            leftSlot = leftSlot,
            rightSlot = rightSlot,
            callState = activeCallState,
            navState = activeNavState,
            cutoutRect = _cutoutInfo.value?.rect,
            modeStartedAtMs = System.currentTimeMillis()
        )
    }

    /**
     * Check if anchor should be visible.
     * Anchor is always visible except when in NOTIF_EXPANDED mode
     * (where the expanded notification replaces it).
     */
    fun shouldShowAnchor(): Boolean {
        return _anchorState.value.mode != IslandMode.NOTIF_EXPANDED && _anchorState.value.mode != IslandMode.NAV_EXPANDED
    }

    /**
     * Get current mode for telemetry.
     */
    fun getCurrentMode(): IslandMode = _anchorState.value.mode

    /**
     * Convert from ActiveIsland to anchor states.
     */
    fun updateFromActiveIsland(island: ActiveIsland?) {
        if (island == null) {
            if (isNotifExpanded) {
                shrinkToAnchor("ISLAND_NULL")
            }
            return
        }

        when (island.featureId) {
            FEATURE_CALL -> {
                val callState = CallAnchorState(
                    notificationKey = island.notificationKey,
                    packageName = island.packageName,
                    callerName = "",
                    durationText = "",
                    isIncoming = false,
                    isActive = !island.isCollapsed
                )
                updateCallState(callState)
            }
            FEATURE_NAVIGATION -> {
                val navState = NavAnchorState(
                    notificationKey = island.notificationKey,
                    packageName = island.packageName
                )
                updateNavState(navState)
            }
            FEATURE_NOTIFICATION -> {
                if (island.isExpanded || !island.isCollapsed) {
                    expandNotification(island.notificationKey)
                } else {
                    shrinkToAnchor("NOTIF_COLLAPSED")
                }
            }
        }
    }
}
