package com.coni.hyperisle.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.coni.hyperisle.util.ContextStateManager

/**
 * Broadcast receiver for context signals (screen on/off, charging state).
 * 
 * Handles:
 * - Intent.ACTION_SCREEN_ON
 * - Intent.ACTION_SCREEN_OFF
 * - Intent.ACTION_POWER_CONNECTED
 * - Intent.ACTION_POWER_DISCONNECTED
 * 
 * Updates ContextStateManager with debouncing (1s for same state).
 * No polling - only reacts to system broadcasts.
 */
class ContextSignalsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ContextSignalsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                ContextStateManager.setScreenOn(context, true)
            }
            Intent.ACTION_SCREEN_OFF -> {
                ContextStateManager.setScreenOn(context, false)
            }
            Intent.ACTION_POWER_CONNECTED -> {
                ContextStateManager.setCharging(context, true)
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                ContextStateManager.setCharging(context, false)
            }
        }
    }
}
