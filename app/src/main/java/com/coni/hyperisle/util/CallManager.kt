package com.coni.hyperisle.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Utility class for managing phone calls.
 * Uses TelecomManager.endCall() API (API 28+) to end active calls.
 */
object CallManager {
    private const val TAG = "CallManager"

    /**
     * Attempts to end the current active call using TelecomManager.
     * 
     * @param context Application context
     * @return true if the call was successfully ended, false otherwise
     */
    @Suppress("DEPRECATION")
    fun endCall(context: Context): Boolean {
        // TelecomManager.endCall() requires API 28+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.d("HyperIsleIsland", "EVT=END_CALL_FAIL reason=API_LEVEL_TOO_LOW api=${Build.VERSION.SDK_INT}")
            return false
        }

        // Check for ANSWER_PHONE_CALLS permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.d("HyperIsleIsland", "EVT=END_CALL_FAIL reason=PERMISSION_DENIED")
            return false
        }

        return try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager == null) {
                Log.d("HyperIsleIsland", "EVT=END_CALL_FAIL reason=NO_TELECOM_SERVICE")
                return false
            }

            val result = telecomManager.endCall()
            Log.d("HyperIsleIsland", "EVT=END_CALL_RESULT result=$result")
            Log.d(TAG, "endCall() returned: $result")
            result
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when ending call", e)
            Log.d("HyperIsleIsland", "EVT=END_CALL_FAIL reason=SECURITY_EXCEPTION msg=${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Exception when ending call", e)
            Log.d("HyperIsleIsland", "EVT=END_CALL_FAIL reason=${e.javaClass.simpleName} msg=${e.message}")
            false
        }
    }

    /**
     * Checks if the app has permission to end calls.
     */
    fun hasEndCallPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == 
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks if the device supports ending calls via TelecomManager.
     */
    fun isEndCallSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    }
}
