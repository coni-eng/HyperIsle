package com.coni.hyperisle.overlay.host

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telecom.TelecomManager
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.overlay.engine.IslandActions
import com.coni.hyperisle.util.CallManager
import com.coni.hyperisle.util.Haptics
import com.coni.hyperisle.util.HiLog




/**
 * Implementation of IslandActions for the overlay service.
 * Provides real implementations of all action callbacks.
 */
class IslandActionsImpl(
    private val context: Context,
    private val onDismiss: (String) -> Unit,
    private val onExpand: () -> Unit,
    private val onCollapse: () -> Unit,
    private val onEnterReply: () -> Unit,
    private val onExitReply: () -> Unit
) : IslandActions {

    companion object {
        private const val TAG = "IslandActions"
    }

    // ==================== OVERLAY CONTROL ====================

    override fun dismiss(reason: String) {
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=ACTION_DISMISS reason=$reason")
        }
        onDismiss(reason)
    }

    override fun expand() {
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=ACTION_EXPAND")
        }
        onExpand()
    }

    override fun collapse() {
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=ACTION_COLLAPSE")
        }
        onCollapse()
    }

    override fun toggleExpand() {
        // This would need current state - delegate to service
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=ACTION_TOGGLE_EXPAND")
        }
        onExpand() // Default to expand
    }

    override fun enterReplyMode() {
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=ACTION_ENTER_REPLY")
        }
        onEnterReply()
    }

    override fun exitReplyMode() {
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=ACTION_EXIT_REPLY")
        }
        onExitReply()
    }

    // ==================== INTENT ACTIONS ====================

    override fun sendPendingIntent(intent: PendingIntent?, actionLabel: String): Boolean {
        if (intent == null) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=SEND_INTENT_FAIL action=$actionLabel reason=NULL_INTENT")
            }
            return false
        }

        return try {
            intent.send(context, 0, null, null, null, null, null)
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=SEND_INTENT_OK action=$actionLabel")
            }
            true
        } catch (e: PendingIntent.CanceledException) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=SEND_INTENT_FAIL action=$actionLabel reason=CANCELED")
            }
            false
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=SEND_INTENT_FAIL action=$actionLabel reason=${e.javaClass.simpleName}")
            }
            false
        }
    }

    override fun sendInlineReply(
        pendingIntent: PendingIntent,
        remoteInputs: Array<RemoteInput>,
        message: String
    ): Boolean {
        val startTime = System.currentTimeMillis()
        return try {
            val intent = Intent()
            val results = Bundle()
            remoteInputs.forEach { input ->
                results.putCharSequence(input.resultKey, message)
            }
            RemoteInput.addResultsToIntent(remoteInputs, intent, results)
            pendingIntent.send(context, 0, intent)
            val latencyMs = System.currentTimeMillis() - startTime
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=REPLY_SEND_OK latencyMs=$latencyMs inputCount=${remoteInputs.size}")
            }
            hapticSuccess()
            true
        } catch (e: PendingIntent.CanceledException) {
            val latencyMs = System.currentTimeMillis() - startTime
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=REPLY_SEND_FAIL err=CANCELED latencyMs=$latencyMs")
            }
            false
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=REPLY_SEND_FAIL err=${e.javaClass.simpleName} latencyMs=$latencyMs msg=${e.message}")
            }
            false
        }
    }

    override fun openApp(packageName: String) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_ISLAND, "EVT=OPEN_APP_OK pkg=$packageName")
                }
            } else {
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_ISLAND, "EVT=OPEN_APP_FAIL pkg=$packageName reason=NO_LAUNCH_INTENT")
                }
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=OPEN_APP_FAIL pkg=$packageName reason=${e.javaClass.simpleName}")
            }
        }
    }

    override fun showInCallScreen() {
        try {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_PHONE_STATE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            
            if (!hasPermission) {
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_ISLAND, "EVT=SHOW_INCALL_SKIP reason=NO_PERMISSION")
                }
                return
            }
            
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecomManager?.showInCallScreen(true)
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=SHOW_INCALL_OK")
            }
            hapticSuccess()
        } catch (e: SecurityException) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=SHOW_INCALL_FAIL reason=SECURITY_EXCEPTION")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=SHOW_INCALL_FAIL reason=${e.javaClass.simpleName}")
            }
        }
    }

    // ==================== CALL ACTIONS ====================

    override fun acceptCall(fallbackIntent: PendingIntent?): Boolean {
        val result = CallManager.acceptCall(context, fallbackIntent)
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=ACCEPT_CALL success=${result.success} method=${result.method}")
        }
        if (result.success) hapticSuccess()
        return result.success
    }

    override fun endCall(fallbackIntent: PendingIntent?): Boolean {
        val result = CallManager.endCall(context, fallbackIntent)
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=END_CALL success=${result.success} method=${result.method}")
        }
        if (result.success) hapticSuccess()
        return result.success
    }

    override fun toggleSpeaker(fallbackIntent: PendingIntent?): Boolean {
        val result = CallManager.toggleSpeaker(context, fallbackIntent)
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=TOGGLE_SPEAKER success=${result.success} method=${result.method}")
        }
        if (result.success) hapticSuccess()
        return result.success
    }

    override fun toggleMute(fallbackIntent: PendingIntent?): Boolean {
        val result = CallManager.toggleMute(context, fallbackIntent)
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=TOGGLE_MUTE success=${result.success} method=${result.method}")
        }
        if (result.success) hapticSuccess()
        return result.success
    }

    // ==================== HAPTICS ====================

    override fun hapticShown() {
        Haptics.hapticOnIslandShown(context)
    }

    override fun hapticSuccess() {
        Haptics.hapticOnIslandSuccess(context)
    }

    // ==================== LOGGING ====================

    override fun logDebug(tag: String, event: String, vararg params: Pair<String, Any?>) {
        if (BuildConfig.DEBUG) {
            val paramsStr = params.joinToString(" ") { "${it.first}=${it.second}" }
            HiLog.d(HiLog.TAG_ISLAND, "EVT=$event $paramsStr")
        }
    }
}