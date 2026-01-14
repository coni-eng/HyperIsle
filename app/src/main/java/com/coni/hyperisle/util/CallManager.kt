package com.coni.hyperisle.util

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.coni.hyperisle.BuildConfig
import com.coni.hyperisle.util.HiLog
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
    
    // ========== HARDENING: CALL SESSION TRACKING ==========
    // CallKey must be generated from call session, NOT notification
    // This ensures callKey is stable throughout a single call
    
    /**
     * Active call session data - truth source for callKey generation.
     * Reset only when call state transitions to IDLE.
     */
    data class CallSession(
        val callHandle: String,           // Phone number or contact identifier
        val direction: String,             // INCOMING or OUTGOING
        val startedAtElapsedRealtime: Long, // SystemClock.elapsedRealtime() at call start
        val callKey: String                // Generated stable key for this session
    )
    
    @Volatile
    private var activeSession: CallSession? = null
    
    @Volatile
    private var lastCallState: CallState = CallState.IDLE
    
    @Volatile
    private var sessionLockedUntil: Long = 0L // HARDENING: Lock session after IDLE transition
    private const val SESSION_LOCK_MS = 3000L // 3 seconds lock after call ends
    
    /**
     * HARDENING: Generate or retrieve stable callKey for the current call session.
     * 
     * Priority order:
     * 1. Existing session callKey (if call is ongoing)
     * 2. New session from callHandle + direction + elapsedRealtime
     * 
     * RULE: Same call = same callKey. New call = new callKey.
     */
    fun getOrCreateCallKey(
        context: Context,
        callHandle: String?,
        direction: String = "UNKNOWN"
    ): String {
        val currentState = getCallState(context)
        val now = android.os.SystemClock.elapsedRealtime()
        
        // HARDENING: If IDLE and within lock period, return empty (no call)
        if (currentState == CallState.IDLE) {
            if (now < sessionLockedUntil) {
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_ISLAND, "EVT=CALLKEY_BLOCKED reason=SESSION_LOCKED lockRemaining=${sessionLockedUntil - now}ms")
                }
                return ""
            }
            // Clear session on IDLE
            if (activeSession != null) {
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_ISLAND, "EVT=CALL_SESSION_CLEARED oldKey=${activeSession?.callKey}")
                }
                activeSession = null
            }
            return ""
        }
        
        // Reuse existing session if available
        val session = activeSession
        if (session != null) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=CALLKEY_REUSED value=${session.callKey}")
            }
            return session.callKey
        }
        
        // Create new session
        val handle = callHandle?.takeIf { it.isNotBlank() } ?: "unknown"
        val newKey = "${handle}_${direction}_${now}"
        val newSession = CallSession(
            callHandle = handle,
            direction = direction,
            startedAtElapsedRealtime = now,
            callKey = newKey
        )
        activeSession = newSession
        
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=CALLKEY_CREATED value=$newKey source=CALL_SESSION handle=$handle direction=$direction")
        }
        
        return newKey
    }
    
    /**
     * HARDENING: Lock call session after IDLE transition.
     * Prevents stale MIUI bridge events from creating new sessions.
     */
    fun lockSessionOnIdle() {
        val now = android.os.SystemClock.elapsedRealtime()
        sessionLockedUntil = now + SESSION_LOCK_MS
        activeSession = null
        
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=SESSION_LOCKED until=${sessionLockedUntil} lockMs=$SESSION_LOCK_MS")
        }
    }
    
    /**
     * Check if we're in session lock period (call just ended).
     */
    fun isSessionLocked(): Boolean {
        return android.os.SystemClock.elapsedRealtime() < sessionLockedUntil
    }
    
    /**
     * Get current active session (for debugging/logging).
     */
    fun getActiveSession(): CallSession? = activeSession

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
        HiLog.d(HiLog.TAG_CALL, "EVT=ACCEPT_CALL_START")
        
        // Check for ANSWER_PHONE_CALLS permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) 
            != PackageManager.PERMISSION_GRANTED) {
            HiLog.d(HiLog.TAG_CALL, "EVT=ACCEPT_CALL_FAIL reason=PERMISSION_DENIED")
            // Try fallback if no permission
            return tryFallbackIntent(fallbackIntent, "PERMISSION_DENIED")
        }

        // Try TelecomManager first (most reliable on MIUI/HyperOS)
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null) {
                // acceptRingingCall() is available on API 26+
                telecomManager.acceptRingingCall()
                HiLog.d(HiLog.TAG_CALL, "EVT=ACCEPT_CALL_OK method=TELECOM")
                HiLog.d(HiLog.TAG_CALL, "acceptRingingCall() called successfully")
                return CallActionResult(
                    success = true,
                    method = "TELECOM",
                    verifiedState = null // Will be verified by caller if needed
                )
            } else {
                HiLog.d(HiLog.TAG_CALL, "EVT=ACCEPT_CALL_FAIL reason=NO_TELECOM_SERVICE")
            }
        } catch (e: SecurityException) {
            HiLog.e(HiLog.TAG_CALL, "SecurityException when accepting call", emptyMap(), e)
            HiLog.d(HiLog.TAG_CALL, "EVT=ACCEPT_CALL_FAIL reason=SECURITY_EXCEPTION msg=${e.message}")
        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_CALL, "Exception when accepting call via TelecomManager", emptyMap(), e)
            HiLog.d(HiLog.TAG_CALL, "EVT=ACCEPT_CALL_FAIL reason=${e.javaClass.simpleName} msg=${e.message}")
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
        HiLog.d(HiLog.TAG_ISLAND, "EVT=END_CALL_START")
        
        // TelecomManager.endCall() requires API 28+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=END_CALL_FAIL reason=API_LEVEL_TOO_LOW api=${Build.VERSION.SDK_INT}")
            return tryFallbackIntent(fallbackIntent, "API_TOO_LOW")
        }

        // Check for ANSWER_PHONE_CALLS permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) 
            != PackageManager.PERMISSION_GRANTED) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=END_CALL_FAIL reason=PERMISSION_DENIED")
            return tryFallbackIntent(fallbackIntent, "PERMISSION_DENIED")
        }

        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null) {
                val result = telecomManager.endCall()
                HiLog.d(HiLog.TAG_ISLAND, "EVT=END_CALL_RESULT result=$result method=TELECOM")
                HiLog.d(HiLog.TAG_ISLAND, "endCall() returned: $result")
                if (result) {
                    return CallActionResult(
                        success = true,
                        method = "TELECOM",
                        verifiedState = null
                    )
                }
            } else {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=END_CALL_FAIL reason=NO_TELECOM_SERVICE")
            }
        } catch (e: SecurityException) {
            HiLog.e(HiLog.TAG_ISLAND, "SecurityException when ending call", emptyMap(), e)
            HiLog.d(HiLog.TAG_ISLAND, "EVT=END_CALL_FAIL reason=SECURITY_EXCEPTION msg=${e.message}")
        } catch (e: Exception) {
            HiLog.e(HiLog.TAG_ISLAND, "Exception when ending call", emptyMap(), e)
            HiLog.d(HiLog.TAG_ISLAND, "EVT=END_CALL_FAIL reason=${e.javaClass.simpleName} msg=${e.message}")
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
            HiLog.d(HiLog.TAG_ISLAND, "EVT=FALLBACK_SKIP reason=NO_INTENT telecomFail=$telecomFailReason")
            return CallActionResult(
                success = false,
                method = "NONE",
                verifiedState = null,
                error = "No fallback intent available"
            )
        }

        return try {
            intent.send()
            HiLog.d(HiLog.TAG_ISLAND, "EVT=FALLBACK_OK method=PENDING_INTENT telecomFail=$telecomFailReason")
            CallActionResult(
                success = true,
                method = "PENDING_INTENT",
                verifiedState = null
            )
        } catch (e: PendingIntent.CanceledException) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=FALLBACK_FAIL reason=CANCELED")
            CallActionResult(
                success = false,
                method = "PENDING_INTENT",
                verifiedState = null,
                error = "PendingIntent canceled"
            )
        } catch (e: Exception) {
            HiLog.d(HiLog.TAG_ISLAND, "EVT=FALLBACK_FAIL reason=${e.javaClass.simpleName}")
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
     * HARDENING: Also tracks state transitions for session management.
     */
    @Suppress("DEPRECATION")
    fun getCallState(context: Context): CallState {
        return try {
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val newState = when (telephonyManager?.callState) {
                TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
                TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
                else -> CallState.UNKNOWN
            }
            
            // HARDENING: Detect IDLE transition and lock session
            if (lastCallState != CallState.IDLE && newState == CallState.IDLE) {
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_ISLAND, "EVT=CALL_STATE_IDLE_TRANSITION old=${lastCallState.name} callKey=${activeSession?.callKey}")
                }
                lockSessionOnIdle()
            }
            
            lastCallState = newState
            newState
        } catch (e: Exception) {
            HiLog.w(HiLog.TAG_ISLAND, "Failed to get call state: ${e.message}")
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
            HiLog.d(HiLog.TAG_ISLAND, "EVT=VERIFY_ACCEPT state=$startState")
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
                HiLog.d(HiLog.TAG_ISLAND, "EVT=VERIFY_ACCEPT_OK elapsed=$elapsed")
                return true
            }
            if (currentState == CallState.IDLE) {
                HiLog.d(HiLog.TAG_ISLAND, "EVT=VERIFY_ACCEPT_FAIL state=IDLE elapsed=$elapsed")
                return false
            }
        }

        HiLog.d(HiLog.TAG_ISLAND, "EVT=VERIFY_ACCEPT_TIMEOUT elapsed=$elapsed")
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

    // ========== AUDIO MANAGER FALLBACKS FOR MIUI actions=0 ==========
    
    /**
     * Toggle speakerphone using AudioManager.
     * PRIMARY fallback when notification action is unavailable (MIUI actions=0).
     * 
     * @param context Application context
     * @param fallbackIntent Optional PendingIntent to try first
     * @return CallActionResult with success status and method used
     */
    @Suppress("DEPRECATION")
    fun toggleSpeaker(context: Context, fallbackIntent: PendingIntent? = null): CallActionResult {
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_CALL, "EVT=TOGGLE_SPEAKER_START hasIntent=${fallbackIntent != null}")
        }
        
        // Try PendingIntent first if available
        if (fallbackIntent != null) {
            val intentResult = tryFallbackIntent(fallbackIntent, "NONE")
            if (intentResult.success) {
                return intentResult
            }
        }
        
        // AudioManager fallback
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val currentState = audioManager.isSpeakerphoneOn
                audioManager.isSpeakerphoneOn = !currentState
                val newState = audioManager.isSpeakerphoneOn
                val toggled = currentState != newState
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_CALL, "EVT=TOGGLE_SPEAKER_RESULT method=AUDIO_MANAGER old=$currentState new=$newState toggled=$toggled")
                }
                CallActionResult(
                    success = toggled,
                    method = "AUDIO_MANAGER",
                    verifiedState = null
                )
            } else {
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_CALL, "EVT=TOGGLE_SPEAKER_FAIL reason=NO_AUDIO_MANAGER")
                }
                CallActionResult(
                    success = false,
                    method = "NONE",
                    verifiedState = null,
                    error = "No AudioManager available"
                )
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_CALL, "EVT=TOGGLE_SPEAKER_FAIL reason=${e.javaClass.simpleName} msg=${e.message}")
            }
            CallActionResult(
                success = false,
                method = "AUDIO_MANAGER",
                verifiedState = null,
                error = e.message
            )
        }
    }

    /**
     * Toggle microphone mute using AudioManager.
     * PRIMARY fallback when notification action is unavailable (MIUI actions=0).
     * 
     * @param context Application context
     * @param fallbackIntent Optional PendingIntent to try first
     * @return CallActionResult with success status and method used
     */
    @Suppress("DEPRECATION")
    fun toggleMute(context: Context, fallbackIntent: PendingIntent? = null): CallActionResult {
        if (BuildConfig.DEBUG) {
            HiLog.d(HiLog.TAG_CALL, "EVT=TOGGLE_MUTE_START hasIntent=${fallbackIntent != null}")
        }
        
        // Try PendingIntent first if available
        if (fallbackIntent != null) {
            val intentResult = tryFallbackIntent(fallbackIntent, "NONE")
            if (intentResult.success) {
                return intentResult
            }
        }
        
        // AudioManager fallback
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val currentState = audioManager.isMicrophoneMute
                audioManager.isMicrophoneMute = !currentState
                val newState = audioManager.isMicrophoneMute
                val toggled = currentState != newState
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_CALL, "EVT=TOGGLE_MUTE_RESULT method=AUDIO_MANAGER old=$currentState new=$newState toggled=$toggled")
                }
                CallActionResult(
                    success = toggled,
                    method = "AUDIO_MANAGER",
                    verifiedState = null
                )
            } else {
                if (BuildConfig.DEBUG) {
                    HiLog.d(HiLog.TAG_CALL, "EVT=TOGGLE_MUTE_FAIL reason=NO_AUDIO_MANAGER")
                }
                CallActionResult(
                    success = false,
                    method = "NONE",
                    verifiedState = null,
                    error = "No AudioManager available"
                )
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                HiLog.d(HiLog.TAG_CALL, "EVT=TOGGLE_MUTE_FAIL reason=${e.javaClass.simpleName} msg=${e.message}")
            }
            CallActionResult(
                success = false,
                method = "AUDIO_MANAGER",
                verifiedState = null,
                error = e.message
            )
        }
    }

    /**
     * Get current speaker state.
     */
    fun isSpeakerOn(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.isSpeakerphoneOn ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get current mute state.
     */
    fun isMuted(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.isMicrophoneMute ?: false
        } catch (e: Exception) {
            false
        }
    }
}
