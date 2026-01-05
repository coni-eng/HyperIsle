package com.d4viddf.hyperbridge.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.service.NotificationReaderService
import com.d4viddf.hyperbridge.worker.NotificationSummaryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Check for both standard boot and quick boot (some ROMs use quick)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {

            Log.d("HyperBridge", "Boot completed detected.")

            // Trick: We toggle the component state to force the Notification Manager
            // to re-evaluate and re-bind to our service.
            toggleNotificationListener(context)

            // Re-schedule summary worker if enabled
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val preferences = AppPreferences(context)
                    val summaryEnabled = preferences.summaryEnabledFlow.first()
                    if (summaryEnabled) {
                        val summaryHour = preferences.summaryHourFlow.first()
                        NotificationSummaryWorker.schedule(context, summaryHour)
                    }
                } catch (e: Exception) {
                    Log.w("HyperBridge", "Failed to schedule summary worker on boot: ${e.message}")
                }
            }
        }
    }

    private fun toggleNotificationListener(context: Context) {
        val pm = context.packageManager
        val componentName = ComponentName(context, NotificationReaderService::class.java)

        // Disable
        pm.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )

        // Enable
        pm.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}