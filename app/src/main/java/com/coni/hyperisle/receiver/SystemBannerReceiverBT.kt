package com.coni.hyperisle.receiver

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.RequiresPermission
import androidx.core.content.edit
import com.coni.hyperisle.R
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.util.Haptics
import com.coni.hyperisle.util.HiLog
import com.coni.hyperisle.util.SystemHyperIslandPoster
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking



/**
 * Broadcast receiver for Bluetooth device connection events.
 * Posts AirPods-style short banner when a BT device connects.
 * Only active if user explicitly enables it (default OFF).
 */
class SystemBannerReceiverBT : BroadcastReceiver() {

    companion object {
        private const val TAG = "SystemBannerReceiverBT"
        private const val NOTIFICATION_ID = 9010
        
        // Debounce interval to prevent duplicate banners
        private const val DEBOUNCE_INTERVAL_MS = 5000L
        
        private const val PREFS_NAME = "bt_banner_state"
        private const val KEY_LAST_DEVICE = "last_device_address"
        private const val KEY_LAST_TIME = "last_connection_time"
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onReceive(context: Context, intent: Intent?) {
        // Security: Validate intent before processing
        val action = intent?.action ?: return
        
        // Security: Whitelist - only accept known Bluetooth actions
        if (action != BluetoothDevice.ACTION_ACL_CONNECTED) {
            return
        }
        
        HiLog.d(HiLog.TAG_ISLAND, "Received BT connection broadcast")

        // Check if banner is enabled (default OFF)
        val preferences = AppPreferences(context)
        val isEnabled = runBlocking { preferences.bannerBtConnectedEnabledFlow.first() }
        if (!isEnabled) {
            HiLog.d(HiLog.TAG_ISLAND, "BT banner disabled, ignoring")
            return
        }

        handleDeviceConnected(context, intent)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun handleDeviceConnected(context: Context, intent: Intent) {
        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val deviceAddress = device?.address ?: return

        // Debounce check
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastDevice = prefs.getString(KEY_LAST_DEVICE, null)
        val lastTime = prefs.getLong(KEY_LAST_TIME, 0L)
        val now = System.currentTimeMillis()

        if (deviceAddress == lastDevice && (now - lastTime) < DEBOUNCE_INTERVAL_MS) {
            HiLog.d(HiLog.TAG_ISLAND, "BT connection debounced")
            return
        }

        // Save state
        prefs.edit {
            putString(KEY_LAST_DEVICE, deviceAddress)
            putLong(KEY_LAST_TIME, now)
        }

        // Post banner
        val poster = SystemHyperIslandPoster(context)
        if (!poster.hasNotificationPermission()) {
            HiLog.d(HiLog.TAG_ISLAND, "No notification permission")
            return
        }

        val title = context.getString(R.string.app_name)
        val message = context.getString(R.string.banner_bt_unknown_device)

        poster.postSystemNotification(NOTIFICATION_ID, title, message)
        Haptics.hapticOnIslandShown(context)
        
        HiLog.d(HiLog.TAG_ISLAND, "Posted BT connected banner")
    }
}
