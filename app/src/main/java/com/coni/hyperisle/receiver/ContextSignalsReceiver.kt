package com.coni.hyperisle.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.coni.hyperisle.util.ContextStateManager
import com.coni.hyperisle.util.HiLog



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

    override fun onReceive(context: Context, intent: Intent?) {
        // Security: Validate intent before processing
        val action = intent?.action ?: return
        
        // Security: Whitelist - only accept known context signal actions
        when (action) {
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
            else -> {
                // Unknown action - silently ignore
                return
            }
        }
        HiLog.d(HiLog.TAG_ISLAND, "Processed context signal")
    }
}
