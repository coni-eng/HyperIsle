package com.coni.hyperisle.overlay

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.MotionEvent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.R
import com.coni.hyperisle.debug.IslandRuntimeDump
import com.coni.hyperisle.debug.IslandUiSnapshotLogger
import com.coni.hyperisle.ui.components.ActiveCallCompactPill
import com.coni.hyperisle.ui.components.ActiveCallExpandedPill
import com.coni.hyperisle.ui.components.IncomingCallPill
import com.coni.hyperisle.ui.components.MediaDot
import com.coni.hyperisle.ui.components.MediaExpandedPill
import com.coni.hyperisle.ui.components.MediaPill
import com.coni.hyperisle.ui.components.MiniNotificationPill
import com.coni.hyperisle.ui.components.NotificationPill
import com.coni.hyperisle.ui.components.NotificationReplyPill
import com.coni.hyperisle.ui.components.TimerDot
import com.coni.hyperisle.ui.components.TimerPill
import com.coni.hyperisle.util.AccessibilityContextSignals
import com.coni.hyperisle.util.AccessibilityContextState
import com.coni.hyperisle.util.ContextStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Foreground service that manages iOS-style pill overlays.
 * Listens to OverlayEventBus and displays appropriate pill overlays.
 */
class IslandOverlayService : Service() {

    companion object {
        private const val TAG = "IslandOverlayService"
        private const val CHANNEL_ID = "ios_pill_overlay_channel"
        private const val NOTIFICATION_ID = 9999

        const val ACTION_START = "com.coni.hyperisle.overlay.START"
        const val ACTION_STOP = "com.coni.hyperisle.overlay.STOP"

        private val CALL_FOREGROUND_PACKAGES = setOf(
            "com.google.android.dialer",
            "com.android.incallui"
        )
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var overlayController: OverlayWindowController

    // Current overlay state
    private var currentCallModel: IosCallOverlayModel? by mutableStateOf(null)
    private var currentNotificationModel: IosNotificationOverlayModel? by mutableStateOf(null)
    private var currentMediaModel: MediaOverlayModel? by mutableStateOf(null)
    private var currentTimerModel: TimerOverlayModel? by mutableStateOf(null)
    private var isNotificationCollapsed: Boolean by mutableStateOf(false)
    private var autoCollapseJob: Job? = null
    private var stopForegroundJob: Job? = null
    private var isForegroundActive = false
    private var deferCallOverlay: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        // INSTRUMENTATION: Overlay service lifecycle
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=OVL_CREATE STAGE=LIFECYCLE ACTION=OVERLAY_SVC_CREATED")
            IslandRuntimeDump.recordOverlay(null, "SERVICE_CREATED", reason = "onCreate")
        }

        overlayController = OverlayWindowController(applicationContext)
        createNotificationChannel()
        startEventCollection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stopping service")
                // INSTRUMENTATION: Overlay service stop
                if (BuildConfig.DEBUG) {
                    Log.d("HyperIsleIsland", "RID=OVL_STOP STAGE=LIFECYCLE ACTION=OVERLAY_SVC_STOP")
                    IslandRuntimeDump.recordOverlay(null, "SERVICE_STOP", reason = "ACTION_STOP")
                }
                stopForeground(true)
                isForegroundActive = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                Log.d(TAG, "Starting foreground service")
                // INSTRUMENTATION: Overlay service start
                if (BuildConfig.DEBUG) {
                    Log.d("HyperIsleIsland", "RID=OVL_START STAGE=LIFECYCLE ACTION=OVERLAY_SVC_START")
                    IslandRuntimeDump.recordOverlay(null, "SERVICE_START", reason = "ACTION_START")
                }
                ensureForeground()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        // INSTRUMENTATION: Overlay service destroy
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=OVL_DEST STAGE=LIFECYCLE ACTION=OVERLAY_SVC_DESTROYED")
            IslandRuntimeDump.recordOverlay(null, "SERVICE_DESTROYED", reason = "onDestroy")
        }
        autoCollapseJob?.cancel()
        stopForegroundJob?.cancel()
        overlayController.removeOverlay()
        serviceScope.cancel()
        isForegroundActive = false
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_ios_overlay),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "iOS-style pill overlay service"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_island)
            .setContentTitle(getString(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun ensureForeground() {
        if (isForegroundActive) return
        startForeground(NOTIFICATION_ID, createForegroundNotification())
        isForegroundActive = true
    }

    private fun scheduleStopIfIdle(reason: String) {
        stopForegroundJob?.cancel()
        stopForegroundJob = serviceScope.launch {
            delay(3000L)
            if (!overlayController.isShowing() &&
                currentCallModel == null &&
                currentNotificationModel == null &&
                currentMediaModel == null &&
                currentTimerModel == null
            ) {
                Log.d("HyperIsleIsland", "RID=OVL_STOP EVT=OVERLAY_SVC_STOP reason=$reason")
                stopForeground(true)
                isForegroundActive = false
                stopSelf()
            }
        }
    }

    private fun startEventCollection() {
        serviceScope.launch {
            OverlayEventBus.events.collectLatest { event ->
                handleOverlayEvent(event)
            }
        }
    }

    private fun handleOverlayEvent(event: OverlayEvent) {
        if (!overlayController.hasOverlayPermission()) {
            Log.w(TAG, "Overlay permission not granted, ignoring event: $event")
            // INSTRUMENTATION: Overlay permission denied
            if (BuildConfig.DEBUG) {
                Log.d("HyperIsleIsland", "RID=OVL_PERM STAGE=OVERLAY ACTION=PERM_DENIED reason=canDrawOverlays_false")
                IslandRuntimeDump.recordOverlay(null, "PERM_DENIED", reason = "canDrawOverlays_false")
                // UI Snapshot: OVERLAY_PERMISSION denied
                val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                    rid = IslandUiSnapshotLogger.rid(),
                    pkg = null,
                    type = "OVERLAY"
                )
                IslandUiSnapshotLogger.logEvent(
                    ctx = snapshotCtx,
                    evt = "OVERLAY_PERMISSION",
                    route = IslandUiSnapshotLogger.Route.APP_OVERLAY,
                    reason = "DENIED"
                )
            }
            return
        }

        when (event) {
            is OverlayEvent.CallEvent -> {
                if (!canRenderOverlay(event.model.notificationKey.hashCode(), allowOnKeyguard = true)) {
                    scheduleStopIfIdle("RENDER_BLOCKED")
                    return
                }
            }
            is OverlayEvent.NotificationEvent -> {
                if (!canRenderOverlay(event.model.notificationKey.hashCode(), allowOnKeyguard = false)) {
                    scheduleStopIfIdle("RENDER_BLOCKED")
                    return
                }
            }
            is OverlayEvent.MediaEvent -> {
                if (!canRenderOverlay(event.model.notificationKey.hashCode(), allowOnKeyguard = false)) {
                    scheduleStopIfIdle("RENDER_BLOCKED")
                    return
                }
            }
            is OverlayEvent.TimerEvent -> {
                if (!canRenderOverlay(event.model.notificationKey.hashCode(), allowOnKeyguard = false)) {
                    scheduleStopIfIdle("RENDER_BLOCKED")
                    return
                }
            }
            else -> Unit
        }

        when (event) {
            is OverlayEvent.CallEvent -> showCallOverlay(event.model)
            is OverlayEvent.MediaEvent -> showMediaOverlay(event.model)
            is OverlayEvent.TimerEvent -> showTimerOverlay(event.model)
            is OverlayEvent.NotificationEvent -> showNotificationOverlay(event.model)
            is OverlayEvent.DismissEvent -> dismissOverlay(event.notificationKey, "NOTIF_REMOVED")
            is OverlayEvent.DismissAllEvent -> dismissAllOverlays("DISMISS_ALL_EVENT")
        }
    }

    private fun canRenderOverlay(rid: Int, allowOnKeyguard: Boolean): Boolean {
        val screenOn = ContextStateManager.getEffectiveScreenOn(applicationContext)
        if (!screenOn) {
            Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_SKIP reason=SCREEN_OFF")
            return false
        }

        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked == true
        if (isKeyguardLocked && !allowOnKeyguard) {
            Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_SKIP reason=KEYGUARD_LOCKED")
            return false
        }

        return true
    }

    private fun showCallOverlay(model: IosCallOverlayModel) {
        val isIncoming = model.state == CallOverlayState.INCOMING
        val isOngoing = model.state == CallOverlayState.ONGOING
        val hasTimer = model.durationText.isNotEmpty()
        val wasCallOverlay =
            overlayController.currentEvent is OverlayEvent.CallEvent && overlayController.isShowing()

        if (deferCallOverlay && currentNotificationModel != null && isOngoing) {
            currentCallModel = model
            Log.d(
                "HyperIsleIsland",
                "RID=${model.notificationKey.hashCode()} EVT=CALL_DEFERRED reason=NOTIF_PEEK state=${model.state.name}"
            )
            return
        }
        deferCallOverlay = false

        // Cancel any auto-dismiss job (calls don't auto-dismiss)
        autoCollapseJob?.cancel()
        stopForegroundJob?.cancel()
        isNotificationCollapsed = false
        currentNotificationModel = null
        currentCallModel = model

        if (!wasCallOverlay) {
            Log.d(TAG, "Showing call overlay for: ${model.callerName}")
            // INSTRUMENTATION: Overlay show call
            if (BuildConfig.DEBUG) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=${model.notificationKey.hashCode()} STAGE=OVERLAY ACTION=OVERLAY_SHOW type=CALL pkg=${model.packageName}"
                )
                IslandRuntimeDump.recordOverlay(
                    null,
                    "OVERLAY_SHOW",
                    reason = "CALL",
                    pkg = model.packageName,
                    overlayType = "CALL"
                )
                // UI Snapshot: OVERLAY_SHOW for call
                val slots = IslandUiSnapshotLogger.slotsCall(
                    hasAvatar = model.avatarBitmap != null,
                    hasCallerName = model.callerName.isNotEmpty(),
                    hasTimer = hasTimer,
                    actionLabels = if (isIncoming) listOf("decline", "accept") else listOf(
                        "hangup",
                        "speaker",
                        "mute"
                    ),
                    isIncoming = isIncoming,
                    isOngoing = isOngoing
                )
                val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                    rid = IslandUiSnapshotLogger.rid(),
                    pkg = model.packageName,
                    type = "CALL",
                    keyHash = model.notificationKey.hashCode().toString()
                )
                IslandUiSnapshotLogger.logEvent(
                    ctx = snapshotCtx,
                    evt = "OVERLAY_SHOW",
                    route = IslandUiSnapshotLogger.Route.APP_OVERLAY,
                    slots = slots
                )
            }

            Log.d(
                "HyperIsleIsland",
                "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_META type=CALL pkg=${model.packageName} state=${model.state.name} titleLen=${model.title.length} nameLen=${model.callerName.length} hasAvatar=${model.avatarBitmap != null} hasTimer=$hasTimer"
            )
        }

        if (wasCallOverlay) {
            return
        }

        overlayController.showOverlay(OverlayEvent.CallEvent(model)) {
            val activeModel = currentCallModel ?: return@showOverlay
            val activeIsOngoing = activeModel.state == CallOverlayState.ONGOING
            var isExpanded by remember(activeModel.notificationKey) { mutableStateOf(false) }
            val contextSignals by AccessibilityContextState.signals.collectAsState()
            val suppressOverlay = shouldSuppressOverlay(
                contextSignals = contextSignals,
                overlayPackage = activeModel.packageName,
                isCall = true
            )
            LaunchedEffect(suppressOverlay, contextSignals.foregroundPackage) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=${activeModel.notificationKey.hashCode()} EVT=OVERLAY_SUPPRESS state=${if (suppressOverlay) "ON" else "OFF"} type=CALL fg=${contextSignals.foregroundPackage ?: "unknown"} pkg=${activeModel.packageName}"
                    )
                }
            }
            if (suppressOverlay) {
                Spacer(modifier = Modifier.height(0.dp))
                return@showOverlay
            }

            val rid = activeModel.notificationKey.hashCode()
            val mediaModel = currentMediaModel
            var isMediaExpanded by remember(activeModel.notificationKey, mediaModel?.notificationKey) {
                mutableStateOf(false)
            }
            LaunchedEffect(mediaModel?.notificationKey) {
                if (mediaModel == null) {
                    isMediaExpanded = false
                }
            }
            val showSplit = activeIsOngoing && mediaModel != null && !isExpanded && !isMediaExpanded
            val layoutState = when {
                !activeIsOngoing -> "call_incoming"
                isExpanded -> "call_expanded"
                isMediaExpanded -> "media_expanded"
                showSplit -> "call_media_split"
                else -> "call_compact"
            }
            LaunchedEffect(layoutState) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=$rid EVT=OVERLAY_LAYOUT type=CALL state=$layoutState"
                    )
                }
            }
            val containerTap: (() -> Unit)? = when {
                activeIsOngoing && isExpanded -> {
                    { isExpanded = false }
                }
                isMediaExpanded -> {
                    { isMediaExpanded = false }
                }
                else -> null
            }
            val containerLongPress: (() -> Unit)? =
                if (activeIsOngoing && !showSplit && !isMediaExpanded) {
                    { isExpanded = true }
                } else {
                    null
                }

            SwipeDismissContainer(
                rid = rid,
                stateLabel = layoutState,
                onDismiss = { dismissFromUser("SWIPE_DISMISSED") },
                onTap = containerTap,
                onLongPress = containerLongPress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                when {
                    !activeIsOngoing -> {
                        IncomingCallPill(
                            title = activeModel.title,
                            name = activeModel.callerName,
                            avatarBitmap = activeModel.avatarBitmap,
                            onDecline = {
                                Log.d(
                                    "HyperIsleIsland",
                                    "RID=$rid EVT=BTN_CALL_DECLINE_CLICK pkg=${activeModel.packageName}"
                                )
                                val result = handleCallAction(
                                    pendingIntent = activeModel.declineIntent,
                                    actionType = "decline",
                                    rid = rid,
                                    pkg = activeModel.packageName
                                )
                                val resultLabel = if (result) "OK" else "FAIL"
                                Log.d(
                                    "HyperIsleIsland",
                                    "RID=$rid EVT=BTN_CALL_DECLINE_RESULT result=$resultLabel pkg=${activeModel.packageName}"
                                )
                                dismissAllOverlays("CALL_DECLINE")
                            },
                            onAccept = {
                                Log.d(
                                    "HyperIsleIsland",
                                    "RID=$rid EVT=BTN_CALL_ACCEPT_CLICK pkg=${activeModel.packageName}"
                                )
                                val result = handleCallAction(
                                    pendingIntent = activeModel.acceptIntent,
                                    actionType = "accept",
                                    rid = rid,
                                    pkg = activeModel.packageName
                                )
                                val resultLabel = if (result) "OK" else "FAIL"
                                Log.d(
                                    "HyperIsleIsland",
                                    "RID=$rid EVT=BTN_CALL_ACCEPT_RESULT result=$resultLabel pkg=${activeModel.packageName}"
                                )
                                dismissAllOverlays("CALL_ACCEPT")
                            },
                            debugRid = rid
                        )
                    }
                    isExpanded -> {
                        ActiveCallExpandedPill(
                            callerLabel = activeModel.callerName,
                            durationText = activeModel.durationText,
                            onHangUp = activeModel.hangUpIntent?.let {
                                {
                                    handleCallAction(
                                        pendingIntent = it,
                                        actionType = "hangup",
                                        rid = rid,
                                        pkg = activeModel.packageName
                                    )
                                }
                            },
                            onSpeaker = activeModel.speakerIntent?.let {
                                {
                                    handleCallAction(
                                        pendingIntent = it,
                                        actionType = "speaker",
                                        rid = rid,
                                        pkg = activeModel.packageName
                                    )
                                }
                            },
                            onMute = activeModel.muteIntent?.let {
                                {
                                    handleCallAction(
                                        pendingIntent = it,
                                        actionType = "mute",
                                        rid = rid,
                                        pkg = activeModel.packageName
                                    )
                                }
                            },
                            debugRid = rid
                        )
                    }
                    isMediaExpanded && mediaModel != null -> {
                        MediaExpandedPill(
                            title = mediaModel.title,
                            subtitle = mediaModel.subtitle,
                            albumArt = mediaModel.albumArt,
                            actions = mediaModel.actions,
                            modifier = Modifier.fillMaxWidth(),
                            debugRid = rid
                        )
                    }
                    showSplit -> {
                        val media = mediaModel ?: return@SwipeDismissContainer
                        val gapWidth by animateDpAsState(
                            targetValue = 10.dp,
                            label = "call_media_gap"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            ActiveCallCompactPill(
                                callerLabel = activeModel.callerName,
                                durationText = activeModel.durationText,
                                modifier = Modifier
                                    .widthIn(min = 180.dp, max = 240.dp)
                                    .combinedClickable(
                                        onClick = {
                                            Log.d(
                                                "HyperIsleIsland",
                                                "RID=$rid EVT=SPLIT_TAP target=CALL pkg=${activeModel.packageName}"
                                            )
                                            handleNotificationTap(
                                                contentIntent = activeModel.contentIntent,
                                                rid = rid,
                                                pkg = activeModel.packageName
                                            )
                                        },
                                        onLongClick = {
                                            isMediaExpanded = false
                                            isExpanded = true
                                        }
                                    ),
                                fillMaxWidth = false,
                                debugRid = rid
                            )
                            Spacer(modifier = Modifier.width(gapWidth))
                            MediaDot(
                                albumArt = media.albumArt,
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        val mediaRid = media.notificationKey.hashCode()
                                        Log.d(
                                            "HyperIsleIsland",
                                            "RID=$mediaRid EVT=SPLIT_TAP target=MEDIA pkg=${media.packageName}"
                                        )
                                        handleNotificationTap(
                                            contentIntent = media.contentIntent,
                                            rid = mediaRid,
                                            pkg = media.packageName
                                        )
                                    },
                                    onLongClick = {
                                        isExpanded = false
                                        isMediaExpanded = true
                                    }
                                ),
                                debugRid = rid
                            )
                        }
                    }
                    else -> {
                        ActiveCallCompactPill(
                            callerLabel = activeModel.callerName,
                            durationText = activeModel.durationText,
                            debugRid = rid
                        )
                    }
                }
            }
        }
    }

    private fun showMediaOverlay(model: MediaOverlayModel) {
        currentMediaModel = model
        if (currentCallModel != null || currentNotificationModel != null) {
            return
        }
        showActivityOverlay(OverlayEvent.MediaEvent(model))
    }

    private fun showTimerOverlay(model: TimerOverlayModel) {
        currentTimerModel = model
        if (currentCallModel != null || currentNotificationModel != null) {
            return
        }
        showActivityOverlay(OverlayEvent.TimerEvent(model))
    }

    private fun showActivityOverlay(event: OverlayEvent) {
        if (currentMediaModel == null && currentTimerModel == null) {
            scheduleStopIfIdle("ACTIVITY_EMPTY")
            return
        }
        val isActivityOverlay =
            overlayController.currentEvent is OverlayEvent.MediaEvent ||
                overlayController.currentEvent is OverlayEvent.TimerEvent
        if (isActivityOverlay && overlayController.isShowing()) {
            return
        }

        autoCollapseJob?.cancel()
        stopForegroundJob?.cancel()
        isNotificationCollapsed = false
        currentNotificationModel = null
        deferCallOverlay = false

        val rid = when (event) {
            is OverlayEvent.MediaEvent -> event.model.notificationKey.hashCode()
            is OverlayEvent.TimerEvent -> event.model.notificationKey.hashCode()
            else -> 0
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=OVERLAY_ACTIVITY_SHOW media=${currentMediaModel != null} timer=${currentTimerModel != null}"
            )
        }

        overlayController.showOverlay(event) {
            ActivityOverlayContent()
        }
    }

    private fun showActivityOverlayFromState() {
        val media = currentMediaModel
        val timer = currentTimerModel
        val event = when {
            media != null -> OverlayEvent.MediaEvent(media)
            timer != null -> OverlayEvent.TimerEvent(timer)
            else -> null
        } ?: return
        showActivityOverlay(event)
    }

    private fun showNotificationOverlay(model: IosNotificationOverlayModel) {
        Log.d(TAG, "Showing notification overlay from: ${model.sender}")
        val rid = model.notificationKey.hashCode()
        val contextSignals = AccessibilityContextState.snapshot()
        val contextRestricted = contextSignals.isFullscreen || contextSignals.isImeVisible
        val callState = currentCallModel?.state
        if (callState == CallOverlayState.INCOMING) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=OVERLAY_SKIP reason=CALL_INCOMING pkg=${model.packageName}"
            )
            return
        }
        val restoreCallAfterDismiss = callState == CallOverlayState.ONGOING
        deferCallOverlay = restoreCallAfterDismiss
        if (contextRestricted) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=OVERLAY_CONTEXT fullscreen=${contextSignals.isFullscreen} ime=${contextSignals.isImeVisible} fg=${contextSignals.foregroundPackage ?: "unknown"}"
            )
        }
        if (restoreCallAfterDismiss) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=OVERLAY_PEEK reason=CALL_ONGOING pkg=${model.packageName}"
            )
        }
        Log.d(
            "HyperIsleIsland",
            "RID=$rid EVT=OVERLAY_META type=NOTIFICATION pkg=${model.packageName} senderLen=${model.sender.length} messageLen=${model.message.length} timeLen=${model.timeLabel.length} hasAvatar=${model.avatarBitmap != null} hasReplyAction=${model.replyAction != null}"
        )
        // INSTRUMENTATION: Overlay show notification
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=${model.notificationKey?.hashCode() ?: 0} STAGE=OVERLAY ACTION=OVERLAY_SHOW type=NOTIFICATION pkg=${model.packageName}")
            IslandRuntimeDump.recordOverlay(null, "OVERLAY_SHOW", reason = "NOTIFICATION", pkg = model.packageName, overlayType = "NOTIFICATION")
            // UI Snapshot: OVERLAY_SHOW for notification
            val slots = IslandUiSnapshotLogger.slotsOverlay(
                hasAvatar = model.avatarBitmap != null,
                hasSender = model.sender.isNotEmpty(),
                hasMessage = model.message.isNotEmpty(),
                hasTime = model.timeLabel.isNotEmpty(),
                overlayType = "notification"
            )
            val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                rid = IslandUiSnapshotLogger.rid(),
                pkg = model.packageName,
                type = "NOTIFICATION",
                keyHash = model.notificationKey?.hashCode()?.toString()
            )
            IslandUiSnapshotLogger.logEvent(
                ctx = snapshotCtx,
                evt = "OVERLAY_SHOW",
                route = IslandUiSnapshotLogger.Route.APP_OVERLAY,
                slots = slots
            )
        }

        // Cancel previous auto-collapse job
        autoCollapseJob?.cancel()
        stopForegroundJob?.cancel()
        if (!restoreCallAfterDismiss) {
            currentCallModel = null
        }
        currentNotificationModel = model
        isNotificationCollapsed = contextRestricted
        scheduleAutoCollapse(model, restoreCallAfterDismiss)

        overlayController.showOverlay(OverlayEvent.NotificationEvent(model)) {
            var isReplying by remember(model.notificationKey) { mutableStateOf(false) }
            var replyText by remember(model.notificationKey) { mutableStateOf("") }
            val replyAction = model.replyAction
            val allowExpand = !contextRestricted
            val allowReply = replyAction != null && !contextRestricted
            val contextState by AccessibilityContextState.signals.collectAsState()
            val suppressOverlay = shouldSuppressOverlay(
                contextSignals = contextState,
                overlayPackage = model.packageName,
                isCall = false
            )
            LaunchedEffect(suppressOverlay, contextState.foregroundPackage) {
                if (BuildConfig.DEBUG) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=$rid EVT=OVERLAY_SUPPRESS state=${if (suppressOverlay) "ON" else "OFF"} type=NOTIFICATION fg=${contextState.foregroundPackage ?: "unknown"} pkg=${model.packageName}"
                    )
                }
            }
            if (suppressOverlay) {
                Spacer(modifier = Modifier.height(0.dp))
                return@showOverlay
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (isReplying) Color(0x99000000) else Color.Transparent),
                contentAlignment = Alignment.TopCenter
            ) {
                SwipeDismissContainer(
                    rid = rid,
                    stateLabel = if (isNotificationCollapsed) "collapsed" else "expanded",
                    onDismiss = { dismissFromUser("SWIPE_DISMISSED") },
                    onTap = {
                        if (isReplying) {
                            Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_IGNORED reason=REPLY_OPEN")
                            return@SwipeDismissContainer
                        }
                        if (isNotificationCollapsed) {
                            if (allowExpand) {
                                isNotificationCollapsed = false
                                scheduleAutoCollapse(model, restoreCallAfterDismiss)
                                Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_EXPAND reason=TAP")
                            } else {
                                Log.d(
                                    "HyperIsleIsland",
                                    "RID=$rid EVT=BTN_TAP_OPEN_CLICK reason=OVERLAY_CONTEXT pkg=${model.packageName}"
                                )
                                Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_TRIGGERED")
                                handleNotificationTap(model.contentIntent, rid, model.packageName)
                                Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_DISMISS_CALLED")
                                dismissFromUser("TAP_OPEN")
                            }
                        } else {
                            Log.d(
                                "HyperIsleIsland",
                                "RID=$rid EVT=BTN_TAP_OPEN_CLICK reason=OVERLAY pkg=${model.packageName}"
                            )
                            Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_TRIGGERED")
                            handleNotificationTap(model.contentIntent, rid, model.packageName)
                            Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_DISMISS_CALLED")
                            dismissFromUser("TAP_OPEN")
                        }
                    },
                    onLongPress = if (allowReply) {
                        {
                            if (isNotificationCollapsed) {
                                isNotificationCollapsed = false
                                scheduleAutoCollapse(model, restoreCallAfterDismiss)
                            }
                            isReplying = true
                            Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_LONG_PRESS")
                            Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_OPEN reason=LONG_PRESS")
                        }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    LaunchedEffect(isNotificationCollapsed) {
                        if (isNotificationCollapsed) {
                            if (isReplying) {
                                Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_CLOSE reason=COLLAPSE")
                            }
                            isReplying = false
                            replyText = ""
                        }
                    }
                    LaunchedEffect(isReplying) {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=$rid EVT=REPLY_STATE state=${if (isReplying) "OPEN" else "CLOSED"}"
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isNotificationCollapsed) {
                            MiniNotificationPill(
                                sender = model.sender,
                                avatarBitmap = model.avatarBitmap,
                                onDismiss = {
                                    Log.d("HyperIsleIsland", "RID=$rid EVT=BTN_RED_X_CLICK reason=OVERLAY")
                                    dismissFromUser("BTN_RED_X")
                                },
                                debugRid = rid
                            )
                        } else if (replyAction != null && isReplying) {
                            NotificationReplyPill(
                                sender = model.sender,
                                message = model.message,
                                avatarBitmap = model.avatarBitmap,
                                replyText = replyText,
                                onReplyChange = { replyText = it },
                                onSend = send@{
                                    val trimmed = replyText.trim()
                                    if (trimmed.isEmpty()) {
                                        Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_FAIL reason=EMPTY_INPUT")
                                        return@send
                                    }
                                    Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_TRY")
                                    val result = sendInlineReply(replyAction, trimmed, rid)
                                        if (result) {
                                            Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_OK")
                                            isReplying = false
                                            replyText = ""
                                            dismissFromUser("REPLY_SENT")
                                        }
                                    },
                                    sendLabel = getString(R.string.overlay_send),   
                                    debugRid = rid
                                )
                        } else {
                            NotificationPill(
                                sender = model.sender,
                                timeLabel = model.timeLabel,
                                message = model.message,
                                avatarBitmap = model.avatarBitmap,
                                onDismiss = {
                                    Log.d("HyperIsleIsland", "RID=$rid EVT=BTN_RED_X_CLICK reason=OVERLAY")
                                    dismissFromUser("BTN_RED_X")
                                },
                                debugRid = rid
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ActivityOverlayContent() {
        val mediaModel = currentMediaModel
        val timerModel = currentTimerModel
        val layoutState = when {
            mediaModel != null && timerModel != null -> "media_timer_split"
            mediaModel != null -> "media_only"
            timerModel != null -> "timer_only"
            else -> "idle"
        }
        val contextSignals by AccessibilityContextState.signals.collectAsState()
        val suppressOverlay = contextSignals.foregroundPackage?.let { fg ->
            (mediaModel?.packageName == fg) || (timerModel?.packageName == fg)
        } ?: false
        val rid = mediaModel?.notificationKey?.hashCode()
            ?: timerModel?.notificationKey?.hashCode()
            ?: 0
        LaunchedEffect(layoutState) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=$rid EVT=OVERLAY_LAYOUT type=ACTIVITY state=$layoutState"
                )
            }
        }
        LaunchedEffect(suppressOverlay, contextSignals.foregroundPackage) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    "HyperIsleIsland",
                    "RID=$rid EVT=OVERLAY_SUPPRESS state=${if (suppressOverlay) "ON" else "OFF"} type=ACTIVITY fg=${contextSignals.foregroundPackage ?: "unknown"}"
                )
            }
        }
        if (suppressOverlay || layoutState == "idle") {
            Spacer(modifier = Modifier.height(0.dp))
            return
        }
        var isMediaExpanded by remember(mediaModel?.notificationKey) { mutableStateOf(false) }
        LaunchedEffect(mediaModel?.notificationKey) {
            if (mediaModel == null) {
                isMediaExpanded = false
            }
        }
        val containerTap: (() -> Unit)? = if (isMediaExpanded) {
            { isMediaExpanded = false }
        } else {
            null
        }

        SwipeDismissContainer(
            rid = rid,
            stateLabel = layoutState,
            onDismiss = { dismissFromUser("SWIPE_DISMISSED") },
            onTap = containerTap,
            onLongPress = null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            when {
                isMediaExpanded && mediaModel != null -> {
                    MediaExpandedPill(
                        title = mediaModel.title,
                        subtitle = mediaModel.subtitle,
                        albumArt = mediaModel.albumArt,
                        actions = mediaModel.actions,
                        modifier = Modifier.fillMaxWidth(),
                        debugRid = rid
                    )
                }
                mediaModel != null && timerModel != null -> {
                    val gapWidth by animateDpAsState(
                        targetValue = 10.dp,
                        label = "media_timer_gap"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        MediaPill(
                            title = mediaModel.title,
                            subtitle = mediaModel.subtitle,
                            albumArt = mediaModel.albumArt,
                            modifier = Modifier
                                .widthIn(min = 200.dp, max = 260.dp)
                                .combinedClickable(
                                    onClick = {
                                        Log.d(
                                            "HyperIsleIsland",
                                            "RID=$rid EVT=SPLIT_TAP target=MEDIA pkg=${mediaModel.packageName}"
                                        )
                                        handleNotificationTap(
                                            contentIntent = mediaModel.contentIntent,
                                            rid = rid,
                                            pkg = mediaModel.packageName
                                        )
                                    },
                                    onLongClick = { isMediaExpanded = true }
                                ),
                            debugRid = rid
                        )
                        Spacer(modifier = Modifier.width(gapWidth))
                        TimerDot(
                            baseTimeMs = timerModel.baseTimeMs,
                            isCountdown = timerModel.isCountdown,
                            modifier = Modifier.combinedClickable(
                                onClick = {
                                    val timerRid = timerModel.notificationKey.hashCode()
                                    Log.d(
                                        "HyperIsleIsland",
                                        "RID=$timerRid EVT=SPLIT_TAP target=TIMER pkg=${timerModel.packageName}"
                                    )
                                    handleNotificationTap(
                                        contentIntent = timerModel.contentIntent,
                                        rid = timerRid,
                                        pkg = timerModel.packageName
                                    )
                                }
                            ),
                            debugRid = rid
                        )
                    }
                }
                mediaModel != null -> {
                    MediaPill(
                        title = mediaModel.title,
                        subtitle = mediaModel.subtitle,
                        albumArt = mediaModel.albumArt,
                        modifier = Modifier
                            .widthIn(min = 220.dp, max = 280.dp)
                            .combinedClickable(
                                onClick = {
                                    handleNotificationTap(
                                        contentIntent = mediaModel.contentIntent,
                                        rid = rid,
                                        pkg = mediaModel.packageName
                                    )
                                },
                                onLongClick = { isMediaExpanded = true }
                            ),
                        debugRid = rid
                    )
                }
                timerModel != null -> {
                    TimerPill(
                        label = timerModel.label,
                        baseTimeMs = timerModel.baseTimeMs,
                        isCountdown = timerModel.isCountdown,
                        modifier = Modifier
                            .widthIn(min = 160.dp, max = 220.dp)
                            .combinedClickable(
                                onClick = {
                                    val timerRid = timerModel.notificationKey.hashCode()
                                    handleNotificationTap(
                                        contentIntent = timerModel.contentIntent,
                                        rid = timerRid,
                                        pkg = timerModel.packageName
                                    )
                                }
                            ),
                        debugRid = rid
                    )
                }
            }
        }
    }

    private fun scheduleAutoCollapse(
        model: IosNotificationOverlayModel,
        restoreCallAfterDismiss: Boolean
    ) {
        autoCollapseJob?.cancel()
        val collapseAfterMs = model.collapseAfterMs
        if (collapseAfterMs == null || collapseAfterMs <= 0L) {
            // Default auto-dismiss after 5 seconds if no collapse time specified
            autoCollapseJob = serviceScope.launch {
                delay(5000L)
                if (currentNotificationModel?.notificationKey == model.notificationKey) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_DISMISS reason=AUTO_TIMEOUT"
                    )
                    dismissNotificationOverlay("AUTO_TIMEOUT", restoreCall = restoreCallAfterDismiss)
                }
            }
            return
        }

        autoCollapseJob = serviceScope.launch {
            // First: collapse after collapseAfterMs
            delay(collapseAfterMs)
            if (currentNotificationModel?.notificationKey == model.notificationKey) {
                if (restoreCallAfterDismiss) {
                    Log.d(
                        "HyperIsleIsland",
                        "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_DISMISS reason=TIMEOUT"
                    )
                    dismissNotificationOverlay("TIMEOUT", restoreCall = true)
                } else if (!isNotificationCollapsed) {
                    isNotificationCollapsed = true
                    Log.d("HyperIsleIsland", "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_COLLAPSE reason=TIMEOUT")
                    
                    // Second: auto-dismiss 3 seconds after collapse
                    delay(3000L)
                    if (currentNotificationModel?.notificationKey == model.notificationKey && isNotificationCollapsed) {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_DISMISS reason=COLLAPSE_TIMEOUT"
                        )
                        dismissNotificationOverlay("COLLAPSE_TIMEOUT", restoreCall = false)
                    }
                }
            }
        }
    }

    private fun sendInlineReply(action: IosNotificationReplyAction, message: String, rid: Int): Boolean {
        return try {
            val intent = Intent()
            val results = Bundle()
            action.remoteInputs.forEach { input ->
                results.putCharSequence(input.resultKey, message)
            }
            RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
            action.pendingIntent.send(this, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_FAIL reason=CANCELED")
            false
        } catch (e: Exception) {
            Log.d("HyperIsleIsland", "RID=$rid EVT=REPLY_SEND_FAIL reason=${e.javaClass.simpleName}")
            false
        }
    }

    private fun handleCallAction(
        pendingIntent: PendingIntent?,
        actionType: String,
        rid: Int,
        pkg: String?
    ): Boolean {
        if (pendingIntent == null) {
            Log.w(TAG, "No PendingIntent for call action: $actionType")
            Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACTION_FAIL action=$actionType reason=NO_INTENT pkg=$pkg")
            return false
        }

        return try {
            pendingIntent.send()
            Log.d(TAG, "Call action sent successfully: $actionType")
            Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACTION_OK action=$actionType pkg=$pkg")
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "PendingIntent was cancelled for action: $actionType", e)
            Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACTION_FAIL action=$actionType reason=CANCELED pkg=$pkg")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send call action: $actionType", e)
            Log.d("HyperIsleIsland", "RID=$rid EVT=CALL_ACTION_FAIL action=$actionType reason=${e.javaClass.simpleName} pkg=$pkg")
            false
        }
    }

    private fun handleNotificationTap(contentIntent: PendingIntent?, rid: Int, pkg: String) {
        if (contentIntent == null) {
            Log.w(TAG, "No contentIntent for notification tap")
            Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_FAIL reason=NO_INTENT pkg=$pkg")
            return
        }

        try {
            contentIntent.send()
            Log.d(TAG, "Notification tap intent sent successfully")
            Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_OK pkg=$pkg")
        } catch (e: PendingIntent.CanceledException) {
            Log.e(TAG, "Notification contentIntent was cancelled", e)
            Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_FAIL reason=CANCELED pkg=$pkg")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notification tap intent", e)
            Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_FAIL reason=${e.javaClass.simpleName} pkg=$pkg")
        }
    }

    private fun dismissOverlay(notificationKey: String?, reason: String) {      
        if (notificationKey == null) {
            dismissAllOverlays(reason)
            return
        }

        // Only dismiss if the key matches current overlay
        val matchesCall = currentCallModel?.notificationKey == notificationKey
        val matchesNotification = currentNotificationModel?.notificationKey == notificationKey
        val matchesMedia = currentMediaModel?.notificationKey == notificationKey
        val matchesTimer = currentTimerModel?.notificationKey == notificationKey

        if (matchesCall && currentNotificationModel != null) {
            Log.d(
                "HyperIsleIsland",
                "RID=${notificationKey.hashCode()} EVT=CALL_CLEARED reason=$reason"
            )
            currentCallModel = null
            deferCallOverlay = false
            return
        }

        if (matchesMedia) {
            currentMediaModel = null
            if (currentCallModel == null && currentNotificationModel == null) {
                if (currentTimerModel != null) {
                    showActivityOverlayFromState()
                } else {
                    dismissAllOverlays(reason)
                }
            }
            return
        }

        if (matchesTimer) {
            currentTimerModel = null
            if (currentCallModel == null && currentNotificationModel == null) {
                if (currentMediaModel != null) {
                    showActivityOverlayFromState()
                } else {
                    dismissAllOverlays(reason)
                }
            }
            return
        }

        if (matchesNotification) {
            val restoreCall = currentCallModel?.state == CallOverlayState.ONGOING
            dismissNotificationOverlay(reason, restoreCall)
            return
        }

        if (matchesCall) {
            currentCallModel = null
            if (currentMediaModel != null || currentTimerModel != null) {
                showActivityOverlayFromState()
            } else {
                dismissAllOverlays(reason)
            }
        }
    }

    private fun dismissNotificationOverlay(reason: String, restoreCall: Boolean) {
        val rid = currentNotificationModel?.notificationKey?.hashCode() ?: 0
        Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_HIDE_CALLED reason=$reason")
        if (BuildConfig.DEBUG) {
            val pkg = currentNotificationModel?.packageName
            Log.d("HyperIsleIsland", "RID=OVL_DISMISS STAGE=OVERLAY ACTION=OVERLAY_DISMISS type=NOTIFICATION pkg=$pkg")
            IslandRuntimeDump.recordOverlay(
                null,
                "OVERLAY_DISMISS",
                reason = "dismissNotification",
                pkg = pkg,
                overlayType = "NOTIFICATION"
            )
            val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                rid = IslandUiSnapshotLogger.rid(),
                pkg = pkg,
                type = "NOTIFICATION"
            )
            IslandUiSnapshotLogger.logEvent(
                ctx = snapshotCtx,
                evt = "OVERLAY_DISMISS",
                route = IslandUiSnapshotLogger.Route.APP_OVERLAY,
                reason = reason
            )
        }
        autoCollapseJob?.cancel()
        currentNotificationModel = null
        isNotificationCollapsed = false
        deferCallOverlay = false
        overlayController.removeOverlay()
        Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_HIDDEN_OK reason=$reason")
        if (restoreCall && currentCallModel != null) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=OVERLAY_RESTORE reason=$reason type=CALL"
            )
            showCallOverlay(currentCallModel ?: return)
        } else if (currentMediaModel != null || currentTimerModel != null) {
            Log.d(
                "HyperIsleIsland",
                "RID=$rid EVT=OVERLAY_RESTORE reason=$reason type=ACTIVITY"
            )
            showActivityOverlayFromState()
        } else {
            scheduleStopIfIdle(reason)
        }
    }

    private fun dismissAllOverlays(reason: String) {
        Log.d(TAG, "Dismissing all overlays")
        val rid = (currentCallModel?.notificationKey ?: currentNotificationModel?.notificationKey)?.hashCode() ?: 0
        Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_HIDE_CALLED reason=$reason")
        // INSTRUMENTATION: Overlay dismiss
        if (BuildConfig.DEBUG) {
            val overlayType = when {
                currentCallModel != null -> "CALL"
                currentNotificationModel != null -> "NOTIFICATION"
                currentMediaModel != null || currentTimerModel != null -> "ACTIVITY"
                else -> "NONE"
            }
            val pkg = currentCallModel?.packageName
                ?: currentNotificationModel?.packageName
                ?: currentMediaModel?.packageName
                ?: currentTimerModel?.packageName
            Log.d("HyperIsleIsland", "RID=OVL_DISMISS STAGE=OVERLAY ACTION=OVERLAY_DISMISS type=$overlayType pkg=$pkg")
            IslandRuntimeDump.recordOverlay(null, "OVERLAY_DISMISS", reason = "dismissAllOverlays", pkg = pkg, overlayType = overlayType)
            // UI Snapshot: OVERLAY_DISMISS
            val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                rid = IslandUiSnapshotLogger.rid(),
                pkg = pkg,
                type = overlayType
            )
            IslandUiSnapshotLogger.logEvent(
                ctx = snapshotCtx,
                evt = "OVERLAY_DISMISS",
                route = IslandUiSnapshotLogger.Route.APP_OVERLAY,
                reason = reason
            )
        }
        autoCollapseJob?.cancel()
        currentCallModel = null
        currentNotificationModel = null
        currentMediaModel = null
        currentTimerModel = null
        isNotificationCollapsed = false
        deferCallOverlay = false
        overlayController.removeOverlay()
        Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_HIDDEN_OK reason=$reason")
        Log.d("HyperIsleIsland", "RID=$rid EVT=STATE_RESET_DONE reason=$reason")
        scheduleStopIfIdle(reason)
    }

    private fun shouldSuppressOverlay(
        contextSignals: AccessibilityContextSignals,
        overlayPackage: String,
        isCall: Boolean
    ): Boolean {
        val foregroundPackage = contextSignals.foregroundPackage ?: return false
        val isForegroundTarget = if (isCall) {
            foregroundPackage == overlayPackage || CALL_FOREGROUND_PACKAGES.contains(foregroundPackage)
        } else {
            foregroundPackage == overlayPackage
        }
        return isForegroundTarget
    }

    private fun dismissFromUser(reason: String) {
        val restoreCall = currentNotificationModel != null &&
            currentCallModel?.state == CallOverlayState.ONGOING
        if (currentNotificationModel != null) {
            dismissNotificationOverlay(reason, restoreCall)
        } else {
            dismissAllOverlays(reason)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    @Composable
    private fun SwipeDismissContainer(
        rid: Int,
        stateLabel: String,
        modifier: Modifier = Modifier,
        onDismiss: () -> Unit,
        onTap: (() -> Unit)? = null,
        onLongPress: (() -> Unit)? = null,
        content: @Composable () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        var offsetX by remember { mutableStateOf(0f) }
        var containerWidth by remember { mutableStateOf(0) }
        var isSwiping by remember { mutableStateOf(false) }
        val density = LocalDensity.current
        val dismissThresholdPx = with(density) { 72.dp.toPx() }
        val state = stateLabel.lowercase()

        Box(
            modifier = modifier
                .onSizeChanged { containerWidth = it.width }
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .pointerInteropFilter { event ->
                    val actionName = when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> "DOWN"
                        MotionEvent.ACTION_MOVE -> "MOVE"
                        MotionEvent.ACTION_UP -> "UP"
                        MotionEvent.ACTION_CANCEL -> "CANCEL"
                        else -> event.actionMasked.toString()
                    }
                    if (BuildConfig.DEBUG) {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=$rid EVT=RAW_TOUCH action=$actionName x=${event.x} y=${event.y} consumed=$isSwiping"
                        )
                    }
                    false
                }
                .pointerInput(rid, onTap, onLongPress) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val pointerId = down.id
                            var longPressTriggered = false
                            var dragTotal = 0f
                            var hasStarted = false
                            var lastPosition = down.position
                            var totalDx = 0f
                            var totalDy = 0f
                            var endedByUp = false
                            var endedByCancel = false
                            val touchSlop = viewConfiguration.touchSlop
                            val longPressJob = if (onLongPress != null) {
                                scope.launch {
                                    delay(viewConfiguration.longPressTimeoutMillis.toLong())
                                    if (!hasStarted && totalDx <= touchSlop && totalDy <= touchSlop) {
                                        longPressTriggered = true
                                        onLongPress.invoke()
                                    }
                                }
                            } else {
                                null
                            }

                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                                val deltaX = change.position.x - lastPosition.x
                                val deltaY = change.position.y - lastPosition.y
                                totalDx = abs(change.position.x - down.position.x)
                                totalDy = abs(change.position.y - down.position.y)

                                if (!hasStarted) {
                                    if (totalDx > touchSlop && totalDx > totalDy) {
                                        hasStarted = true
                                        isSwiping = true
                                        longPressJob?.cancel()
                                        Log.d(
                                            "HyperIsleIsland",
                                            "RID=$rid EVT=SWIPE_START x=${down.position.x} y=${down.position.y} state=$state"
                                        )
                                    }
                                }

                                if (hasStarted && deltaX != 0f) {
                                    dragTotal += deltaX
                                    offsetX += deltaX
                                    change.consume()
                                    Log.d("HyperIsleIsland", "RID=$rid EVT=SWIPE_MOVE dx=$dragTotal")
                                }

                                val isUp = change.previousPressed && !change.pressed
                                val isCancel = !change.pressed && !change.previousPressed
                                if (isUp || isCancel) {
                                    endedByUp = isUp
                                    endedByCancel = isCancel
                                    break
                                }
                                lastPosition = change.position
                            }

                            longPressJob?.cancel()
                            if (!hasStarted) {
                                isSwiping = false
                                if (endedByUp && !endedByCancel && !longPressTriggered &&
                                    totalDx <= touchSlop && totalDy <= touchSlop
                                ) {
                                    onTap?.invoke()
                                }
                                continue
                            }

                            val shouldDismiss = abs(offsetX) >= dismissThresholdPx
                            if (shouldDismiss) {
                                val targetDistance = if (containerWidth > 0) {
                                    containerWidth.toFloat()
                                } else {
                                    dismissThresholdPx * 4
                                }
                                val target = if (offsetX < 0f) -targetDistance else targetDistance
                                scope.launch {
                                    animate(
                                        initialValue = offsetX,
                                        targetValue = target,
                                        animationSpec = tween(180)
                                    ) { value, _ ->
                                        offsetX = value
                                    }
                                    onDismiss()
                                    offsetX = 0f
                                }
                                Log.d(
                                    "HyperIsleIsland",
                                    "RID=$rid EVT=SWIPE_END result=DISMISSED dx=$dragTotal threshold=${dismissThresholdPx.roundToInt()}"
                                )
                            } else {
                                scope.launch {
                                    animate(
                                        initialValue = offsetX,
                                        targetValue = 0f,
                                        animationSpec = spring(stiffness = Spring.StiffnessMedium)
                                    ) { value, _ ->
                                        offsetX = value
                                    }
                                }
                                Log.d(
                                    "HyperIsleIsland",
                                    "RID=$rid EVT=SWIPE_END result=CANCELLED dx=$dragTotal threshold=${dismissThresholdPx.roundToInt()}"
                                )
                            }
                            isSwiping = false
                        }
                    }
                },
            contentAlignment = Alignment.TopCenter
        ) {
            content()
        }
    }
}



