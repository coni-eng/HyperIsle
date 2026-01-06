package com.coni.hyperisle.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.coni.hyperisle.data.AppPreferences
import com.coni.hyperisle.service.NotificationReaderService
import com.coni.hyperisle.worker.NotificationSummaryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Security: Validate intent before processing
        val action = intent?.action ?: return
        
        // Whitelist: Only accept known boot actions
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }
        
        Log.d("HyperIsle", "Boot completed detected.")

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
                Log.w("HyperIsle", "Failed to schedule summary worker on boot: ${e.message}")
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