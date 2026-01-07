package com.d4viddf.hyperisland_kit.demo

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log // Import Log
import android.widget.Toast

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        // --- DEFINE THE SPECIFIC ACTIONS WE WILL USE ---
        private const val TAG = "NotifActionReceiver" // Tag for logging

        /** This action will show a toast AND cancel the notification. */
        const val ACTION_CLICK_AND_CANCEL = "com.d4viddf.hyperisland_kit.demo.ACTION_CLICK_AND_CANCEL"

        /** This action will ONLY show a toast and NOT cancel. */
        const val ACTION_SHOW_TOAST_ONLY = "com.d4viddf.hyperisland_kit.demo.ACTION_SHOW_TOAST_ONLY"

        // Define extras keys
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_TOAST_MESSAGE = "toast_message"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive triggered with action: ${intent.action}")

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notificationId == -1) {
            Log.e(TAG, "Invalid Notification ID (-1). Aborting.")
            return // Invalid ID, do nothing
        }

        Log.d(TAG, "Processing action for notification ID: $notificationId")

        val toastMessage = intent.getStringExtra(EXTRA_TOAST_MESSAGE)

        // Show the toast message if one was provided
        if (toastMessage != null) {
            Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
            Log.d(TAG, "Showing toast: $toastMessage")
        }

        // Only cancel the notification if the action is CLICK_AND_CANCEL
        if (intent.action == ACTION_CLICK_AND_CANCEL) {
            Log.d(TAG, "Action is CLICK_AND_CANCEL. Cancelling notification.")
            notificationManager.cancel(notificationId)
        } else {
            Log.d(TAG, "Action is SHOW_TOAST_ONLY. Not cancelling.")
        }
    }
}