package com.coni.hyperisle.util

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Utility class for managing phone calls.
 * 
 * Uses TelecomManager APIs (API 26+/28+) for reliable call control on MIUI/HyperOS.
 * 
 * Key insight: PendingIntent from notification actions often fails on MIUI because
 * the system intercepts call intents. TelecomManager is the primary method.
 */
object CallManager {
    private const val TAG = "CallManager"

    /**
     * Call state for verification after accept/reject actions.
     */
    enum class CallState {
        IDLE,       // No active call
        RINGING,    // Incoming call ringing
        OFFHOOK,    // Call active (answered)
        UNKNOWN     // Cannot determine
    }

    /**
     * Result of a call action attempt.
     */
    data class CallActionResult(
        val success: Boolean,
        val method: String,          // TELECOM, PENDING_INTENT, FALLBACK
        val verifiedState: CallState?,
        val error: String? = null
    )

    /**
     * Attempts to accept an incoming call using TelecomManager.
     * 
     * PRIMARY method: TelecomManager.acceptRingingCall() (API 26+)
     * FALLBACK: PendingIntent from notification action
     * 
     * @param context Application context
     * @param fallbackIntent Optional PendingIntent to use if TelecomManager fails
     * @return CallActionResult with success status and method used
     */
    @Suppress("DEPRECATION")
    fun acceptCall(context: Context, fallbackIntent: PendingIntent? = null): CallActionResult {
        Log.d("HyperIsleIsland", "EVT=ACCEPT_CALL_START")
        
        // Check for ANSWER_PHONE_CALLS permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.d("HyperIsleIsland", "EVT=ACCEPT_CALL_FAIL reason=PERMISSION_DENIED")
            // Try fallback if no permission
            return tryFallbackIntent(fallbackIntent, "PERMISSION_DENIED")
        }

        // Try TelecomManager first (most reliable on MIUI/HyperOS)
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null) {
                // acceptRingingCall() is available on API 26+
                telecomManager.acceptRingingCall()
                Log.d("HyperIsleIsland", "EVT=ACCEPT_CALL_OK method=TELECOM")
                Log.d(TAG, "acceptRingingCall() called successfully")
                return CallActionResult(
                    success = true,
                    method = "TELECOM",
                    verifiedState = null // Will be verified by caller if needed
                )
            } else {
                Log.d("HyperIsleIsland", "EVT=ACCEPT_CALL_FAIL reason=NO_TELECOM_SERVICE")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when accepting call", e)
            Log.d("HyperIsleIsland", "EVT=ACCEPT_CALL_FAIL reason=SECURITY_EXCEPTION msg=${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception when accepting call via TelecomManager", e)
            Log.d("HyperIsleIsland", "EVT=ACCEPT_CALL_FAIL reason=${e.javaClass.simpleName} msg=${e.message}")
        }

        // TelecomManager failed, try fallback
        return tryFallbackIntent(fallbackIntent, "TELECOM_FAILED")
    }

    /**
     * Attempts to end the current active call using TelecomManager.
     * 
     * @param context Application context
     * @param fallbackIntent Optional PendingIntent to use if TelecomManager fails
     * @return CallActionResult with success status and method used
     */
    @Suppress("DEPRECATION")
    fun endCall(context: Context, fallbackIntent: PendingIntent? = null): CallActionResult {
        Log.d("HyperIsleIsland", "EVT=END_CALL_START")
        
        // TelecomManager.endCall() requires API 28+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.d("HyperIsleIsland", "EVT=END_CALL_FAIL reason=API_LEVEL_TOO_LOW api=${Build.VERSION.SDK_INT}")
            return tryFallbackIntent(fallbackIntent, "API_TOO_LOW")
        }

        // Check for ANSWER_PHONE_CALLS permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.d("HyperIsleIsland", "EVT=END_CALL_FAIL reason=PERMISSION_DENIED")
            return tryFallbackIntent(fallbackIntent, "PERMISSION_DENIED")
        }

        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null) {
                val result = telecomManager.endCall()
                Log.d("HyperIsleIsland", "EVT=END_CALL_RESULT result=$result method=TELECOM")
                Log.d(TAG, "endCall() returned: $result")
                if (result) {
                    return CallActionResult(
                        success = true,
                        method = "TELECOM",
                        verifiedState = null
                    )
                }
            } else {
                Log.d("HyperIsleIsland", "EVT=END_CALL_FAIL reason=NO_TELECOM_SERVICE")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when ending call", e)
            Log.d("HyperIsleIsland", "EVT=END_CALL_FAIL reason=SECURITY_EXCEPTION msg=${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Exception when ending call", e)
            Log.d("HyperIsleIsland", "EVT=END_CALL_FAIL reason=${e.javaClass.simpleName} msg=${e.message}")
        }

        // TelecomManager failed, try fallback
        return tryFallbackIntent(fallbackIntent, "TELECOM_FAILED")
    }

    /**
     * Legacy method for backward compatibility.
     */
    @Suppress("DEPRECATION")
    fun endCall(context: Context): Boolean {
        return endCall(context, null).success
    }

    /**
     * Try to execute a fallback PendingIntent.
     */
    private fun tryFallbackIntent(intent: PendingIntent?, telecomFailReason: String): CallActionResult {
        if (intent == null) {
            Log.d("HyperIsleIsland", "EVT=FALLBACK_SKIP reason=NO_INTENT telecomFail=$telecomFailReason")
            return CallActionResult(
                success = false,
                method = "NONE",
                verifiedState = null,
                error = "No fallback intent available"
            )
        }

        return try {
            intent.send()
            Log.d("HyperIsleIsland", "EVT=FALLBACK_OK method=PENDING_INTENT telecomFail=$telecomFailReason")
            CallActionResult(
                success = true,
                method = "PENDING_INTENT",
                verifiedState = null
            )
        } catch (e: PendingIntent.CanceledException) {
            Log.d("HyperIsleIsland", "EVT=FALLBACK_FAIL reason=CANCELED")
            CallActionResult(
                success = false,
                method = "PENDING_INTENT",
                verifiedState = null,
                error = "PendingIntent canceled"
            )
        } catch (e: Exception) {
            Log.d("HyperIsleIsland", "EVT=FALLBACK_FAIL reason=${e.javaClass.simpleName}")
            CallActionResult(
                success = false,
                method = "PENDING_INTENT",
                verifiedState = null,
                error = e.message
            )
        }
    }

    /**
     * Get current call state for verification.
     * 
     * Use this after accept/reject to verify the action worked.
     */
    @Suppress("DEPRECATION")
    fun getCallState(context: Context): CallState {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            when (telephonyManager?.callState) {
                TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
                TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
                else -> CallState.UNKNOWN
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get call state: ${e.message}")
            CallState.UNKNOWN
        }
    }

    /**
     * Verify call was accepted by checking state transition.
     * 
     * @param context Application context
     * @param maxWaitMs Maximum time to wait for state change
     * @return true if call state changed from RINGING to OFFHOOK
     */
    suspend fun verifyCallAccepted(context: Context, maxWaitMs: Long = 2000L): Boolean {
        val startState = getCallState(context)
        if (startState != CallState.RINGING) {
            // Already not ringing - might be accepted or ended
            Log.d("HyperIsleIsland", "EVT=VERIFY_ACCEPT state=$startState")
            return startState == CallState.OFFHOOK
        }

        // Poll for state change
        val pollInterval = 200L
        var elapsed = 0L
        while (elapsed < maxWaitMs) {
            delay(pollInterval)
            elapsed += pollInterval
            
            val currentState = getCallState(context)
            if (currentState == CallState.OFFHOOK) {
                Log.d("HyperIsleIsland", "EVT=VERIFY_ACCEPT_OK elapsed=$elapsed")
                return true
            }
            if (currentState == CallState.IDLE) {
                Log.d("HyperIsleIsland", "EVT=VERIFY_ACCEPT_FAIL state=IDLE elapsed=$elapsed")
                return false
            }
        }

        Log.d("HyperIsleIsland", "EVT=VERIFY_ACCEPT_TIMEOUT elapsed=$elapsed")
        return false
    }

    /**
     * Checks if the app has permission to manage calls.
     */
    fun hasCallPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == 
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if the device supports call management via TelecomManager.
     */
    fun isCallManagementSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O // API 26 for acceptRingingCall
    }

    /**
     * Checks if the device supports ending calls via TelecomManager.
     */
    fun isEndCallSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P // API 28 for endCall
    }
}
