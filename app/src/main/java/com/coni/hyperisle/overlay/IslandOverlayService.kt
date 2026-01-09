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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.coni.hyperisle.ui.components.IncomingCallPill
import com.coni.hyperisle.ui.components.MiniNotificationPill
import com.coni.hyperisle.ui.components.NotificationPill
import com.coni.hyperisle.ui.components.NotificationReplyPill
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
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var overlayController: OverlayWindowController

    // Current overlay state
    private var currentCallModel: IosCallOverlayModel? by mutableStateOf(null)
    private var currentNotificationModel: IosNotificationOverlayModel? by mutableStateOf(null)
    private var isNotificationCollapsed: Boolean by mutableStateOf(false)
    private var autoCollapseJob: Job? = null
    private var stopForegroundJob: Job? = null
    private var isForegroundActive = false

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
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "iOS-style pill overlay service"
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_service_active))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
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
            if (!overlayController.isShowing() && currentCallModel == null && currentNotificationModel == null) {
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
            else -> Unit
        }

        when (event) {
            is OverlayEvent.CallEvent -> showCallOverlay(event.model)
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
        Log.d(TAG, "Showing call overlay for: ${model.callerName}")
        // INSTRUMENTATION: Overlay show call
        if (BuildConfig.DEBUG) {
            Log.d("HyperIsleIsland", "RID=${model.notificationKey?.hashCode() ?: 0} STAGE=OVERLAY ACTION=OVERLAY_SHOW type=CALL pkg=${model.packageName}")
            IslandRuntimeDump.recordOverlay(null, "OVERLAY_SHOW", reason = "CALL", pkg = model.packageName, overlayType = "CALL")
            // UI Snapshot: OVERLAY_SHOW for call
            val slots = IslandUiSnapshotLogger.slotsCall(
                hasAvatar = model.avatarBitmap != null,
                hasCallerName = model.callerName.isNotEmpty(),
                hasTimer = false,
                actionLabels = listOf("decline", "accept"),
                isIncoming = true,
                isOngoing = false
            )
            val snapshotCtx = IslandUiSnapshotLogger.ctxSynthetic(
                rid = IslandUiSnapshotLogger.rid(),
                pkg = model.packageName,
                type = "CALL",
                keyHash = model.notificationKey?.hashCode()?.toString()
            )
            IslandUiSnapshotLogger.logEvent(
                ctx = snapshotCtx,
                evt = "OVERLAY_SHOW",
                route = IslandUiSnapshotLogger.Route.APP_OVERLAY,
                slots = slots
            )
        }

        // Cancel any auto-dismiss job (calls don't auto-dismiss)
        autoCollapseJob?.cancel()
        stopForegroundJob?.cancel()
        isNotificationCollapsed = false
        currentNotificationModel = null
        currentCallModel = model

        Log.d(
            "HyperIsleIsland",
            "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_META type=CALL pkg=${model.packageName} titleLen=${model.title.length} nameLen=${model.callerName.length} hasAvatar=${model.avatarBitmap != null}"
        )

        overlayController.showOverlay(OverlayEvent.CallEvent(model)) {
            SwipeDismissContainer(
                rid = model.notificationKey.hashCode(),
                stateLabel = "expanded",
                onDismiss = { dismissFromUser("SWIPE_DISMISSED") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                IncomingCallPill(
                    title = model.title,
                    name = model.callerName,
                    avatarBitmap = model.avatarBitmap,
                    onDecline = {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=${model.notificationKey.hashCode()} EVT=BTN_CALL_DECLINE_CLICK pkg=${model.packageName}"
                        )
                        val result = handleCallAction(
                            pendingIntent = model.declineIntent,
                            actionType = "decline",
                            rid = model.notificationKey.hashCode(),
                            pkg = model.packageName
                        )
                        Log.d(
                            "HyperIsleIsland",
                            "RID=${model.notificationKey.hashCode()} EVT=BTN_CALL_DECLINE_RESULT result=${if (result) "OK" else "FAIL"} pkg=${model.packageName}"
                        )
                        dismissAllOverlays("CALL_DECLINE")
                    },
                    onAccept = {
                        Log.d(
                            "HyperIsleIsland",
                            "RID=${model.notificationKey.hashCode()} EVT=BTN_CALL_ACCEPT_CLICK pkg=${model.packageName}"
                        )
                        val result = handleCallAction(
                            pendingIntent = model.acceptIntent,
                            actionType = "accept",
                            rid = model.notificationKey.hashCode(),
                            pkg = model.packageName
                        )
                        Log.d(
                            "HyperIsleIsland",
                            "RID=${model.notificationKey.hashCode()} EVT=BTN_CALL_ACCEPT_RESULT result=${if (result) "OK" else "FAIL"} pkg=${model.packageName}"
                        )
                        dismissAllOverlays("CALL_ACCEPT")
                    },
                    debugRid = model.notificationKey.hashCode()
                )
            }
        }
    }

    private fun showNotificationOverlay(model: IosNotificationOverlayModel) {
        Log.d(TAG, "Showing notification overlay from: ${model.sender}")
        val rid = model.notificationKey.hashCode()
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
        currentCallModel = null
        currentNotificationModel = model
        isNotificationCollapsed = false
        scheduleAutoCollapse(model)

        overlayController.showOverlay(OverlayEvent.NotificationEvent(model)) {
            var isReplying by remember(model.notificationKey) { mutableStateOf(false) }
            var replyText by remember(model.notificationKey) { mutableStateOf("") }
            val replyAction = model.replyAction

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
                            isNotificationCollapsed = false
                            scheduleAutoCollapse(model)
                            Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_EXPAND reason=TAP")
                        } else {
                            Log.d(
                                "HyperIsleIsland",
                                "RID=$rid EVT=BTN_TAP_OPEN_CLICK reason=OVERLAY pkg=${model.packageName}"
                            )
                            Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_TRIGGERED")
                            handleNotificationTap(model.contentIntent, rid, model.packageName)
                            Log.d("HyperIsleIsland", "RID=$rid EVT=TAP_OPEN_DISMISS_CALLED")
                            dismissAllOverlays("TAP_OPEN")
                        }
                    },
                    onLongPress = if (replyAction != null) {
                        {
                            if (isNotificationCollapsed) {
                                isNotificationCollapsed = false
                                scheduleAutoCollapse(model)
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
                                        dismissAllOverlays("REPLY_SENT")
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

    private fun scheduleAutoCollapse(model: IosNotificationOverlayModel) {
        autoCollapseJob?.cancel()
        val collapseAfterMs = model.collapseAfterMs
        if (collapseAfterMs == null || collapseAfterMs <= 0L) return

        autoCollapseJob = serviceScope.launch {
            delay(collapseAfterMs)
            if (currentNotificationModel?.notificationKey == model.notificationKey && !isNotificationCollapsed) {
                isNotificationCollapsed = true
                Log.d("HyperIsleIsland", "RID=${model.notificationKey.hashCode()} EVT=OVERLAY_COLLAPSE reason=TIMEOUT")
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
        val shouldDismiss = when {
            currentCallModel?.notificationKey == notificationKey -> true
            currentNotificationModel?.notificationKey == notificationKey -> true
            else -> false
        }

        if (shouldDismiss) {
            dismissAllOverlays(reason)
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
                else -> "NONE"
            }
            val pkg = currentCallModel?.packageName ?: currentNotificationModel?.packageName
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
        isNotificationCollapsed = false
        overlayController.removeOverlay()
        Log.d("HyperIsleIsland", "RID=$rid EVT=OVERLAY_HIDDEN_OK reason=$reason")
        Log.d("HyperIsleIsland", "RID=$rid EVT=STATE_RESET_DONE reason=$reason")
        scheduleStopIfIdle(reason)
    }

    private fun dismissFromUser(reason: String) {
        dismissAllOverlays(reason)
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
