package com.d4viddf.hyperbridge.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.RequiresPermission
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.util.ContextStateManager
import com.d4viddf.hyperbridge.util.Haptics
import com.d4viddf.hyperbridge.util.SystemHyperIslandPoster
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Broadcast receiver for battery low events.
 * Posts AirPods-style short banner when battery is low.
 * Only active if user explicitly enables it (default OFF).
 */
class SystemBannerReceiverBattery : BroadcastReceiver() {

    companion object {
        private const val TAG = "SystemBannerBattery"
        private const val NOTIFICATION_ID = 9011
        
        // Debounce interval to prevent duplicate banners
        private const val DEBOUNCE_INTERVAL_MS = 60000L // 1 minute
        
        private const val PREFS_NAME = "battery_banner_state"
        private const val KEY_LAST_TIME = "last_low_battery_time"
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")

        // Check if banner is enabled (default OFF)
        val preferences = AppPreferences(context)
        val isEnabled = runBlocking { preferences.bannerBatteryLowEnabledFlow.first() }
        if (!isEnabled) {
            Log.d(TAG, "Battery banner disabled, ignoring")
            return
        }

        // Context-Aware: Suppress battery banners while charging (v0.7.0)
        val contextAwareEnabled = runBlocking { preferences.contextAwareEnabledFlow.first() }
        val suppressWhileCharging = runBlocking { preferences.contextChargingSuppressBatteryBannersFlow.first() }
        if (contextAwareEnabled && suppressWhileCharging && ContextStateManager.getCharging()) {
            Log.d(TAG, "Context-aware: Suppressing battery banner (device is charging)")
            return
        }

        when (intent.action) {
            Intent.ACTION_BATTERY_LOW -> {
                handleBatteryLow(context)
            }
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun handleBatteryLow(context: Context) {
        // Debounce check
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastTime = prefs.getLong(KEY_LAST_TIME, 0L)
        val now = System.currentTimeMillis()

        if ((now - lastTime) < DEBOUNCE_INTERVAL_MS) {
            Log.d(TAG, "Battery low debounced")
            return
        }

        // Save state
        prefs.edit()
            .putLong(KEY_LAST_TIME, now)
            .apply()

        // Post banner
        val poster = SystemHyperIslandPoster(context)
        if (!poster.hasNotificationPermission()) {
            Log.d(TAG, "No notification permission")
            return
        }

        val title = context.getString(R.string.app_name)
        val message = context.getString(R.string.banner_battery_low)

        poster.postSystemNotification(NOTIFICATION_ID, title, message)
        Haptics.hapticOnIslandShown(context)
        
        Log.d(TAG, "Posted battery low banner")
    }
}
