package com.d4viddf.hyperbridge.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.d4viddf.hyperbridge.R
import io.github.d4viddf.hyperisland_kit.HyperIslandNotification
import io.github.d4viddf.hyperisland_kit.HyperPicture
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoLeft
import io.github.d4viddf.hyperisland_kit.models.ImageTextInfoRight
import io.github.d4viddf.hyperisland_kit.models.PicInfo
import io.github.d4viddf.hyperisland_kit.models.TextInfo

/**
 * Helper class to post system mode change notifications as Hyper Island notifications.
 * Falls back to standard Android notifications if Hyper Island is not supported.
 */
class SystemHyperIslandPoster(private val context: Context) {

    companion object {
        private const val TAG = "SystemHyperIslandPoster"
        
        // Hyper Island channel for system state notifications
        const val HYPER_ISLAND_SYSTEM_CHANNEL_ID = "hyperisle_system_island_channel"
        
        // Fallback channel for standard notifications
        const val FALLBACK_CHANNEL_ID = "system_modes_channel"
        
        // Notification IDs
        const val RINGER_MODE_NOTIFICATION_ID = 9001
        const val DND_NOTIFICATION_ID = 9002
    }

    init {
        createChannels()
    }

    private fun createChannels() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)

        // Hyper Island system channel - HIGH importance for island behavior, no sound/vibration
        val hyperIslandChannel = NotificationChannel(
            HYPER_ISLAND_SYSTEM_CHANNEL_ID,
            context.getString(R.string.channel_system_island),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(hyperIslandChannel)

        // Fallback channel - LOW importance, silent
        val fallbackChannel = NotificationChannel(
            FALLBACK_CHANNEL_ID,
            context.getString(R.string.channel_system_modes),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(fallbackChannel)
    }

    /**
     * Check if we have POST_NOTIFICATIONS permission (Android 13+)
     */
    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Post a system state notification as Hyper Island.
     * Falls back to standard notification if MIUI extras are ignored.
     */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun postSystemNotification(
        notificationId: Int,
        title: String,
        message: String
    ) {
        if (!hasNotificationPermission()) {
            Log.d(TAG, "No notification permission, skipping post")
            return
        }

        try {
            // Try Hyper Island first
            postHyperIslandNotification(notificationId, title, message)
        } catch (e: Exception) {
            Log.w(TAG, "Hyper Island posting failed, falling back to standard", e)
            postFallbackNotification(notificationId, title, message)
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun postHyperIslandNotification(
        notificationId: Int,
        title: String,
        message: String
    ) {
        val islandKey = "system_mode_$notificationId"
        val picKey = "pic_system_$notificationId"

        val builder = HyperIslandNotification.Builder(context, islandKey, title)
        
        // Configure for brief display
        builder.setEnableFloat(true)
        builder.setTimeout(3000L) // 3 seconds
        builder.setShowNotification(false) // Don't keep in shade

        // Add app icon as picture
        val iconBitmap = getAppIconBitmap()
        builder.addPicture(HyperPicture(picKey, iconBitmap))

        // Set base info
        builder.setBaseInfo(
            title = title,
            content = message,
            pictureKey = picKey,
            actionKeys = emptyList()
        )

        // Set big island info
        val hiddenKey = "hidden_system"
        val transparentBitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        builder.addPicture(HyperPicture(hiddenKey, transparentBitmap))

        builder.setBigIslandInfo(
            left = ImageTextInfoLeft(1, PicInfo(1, picKey), TextInfo("", "")),
            right = ImageTextInfoRight(1, PicInfo(1, hiddenKey), TextInfo(title, message))
        )

        builder.setSmallIsland(picKey)

        // Build notification with MIUI extras
        val notificationBuilder = NotificationCompat.Builder(context, HYPER_ISLAND_SYSTEM_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(3000L) // Auto-dismiss after 3 seconds
            .addExtras(builder.buildResourceBundle())

        val notification = notificationBuilder.build()
        notification.extras.putString("miui.focus.param", builder.buildJsonParam())

        NotificationManagerCompat.from(context).notify(notificationId, notification)
        Log.d(TAG, "Posted Hyper Island notification: $message")
    }

    private fun getAppIconBitmap(): Bitmap {
        return try {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            // Fallback to a simple colored bitmap
            Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        }
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun postFallbackNotification(
        notificationId: Int,
        title: String,
        message: String
    ) {
        val notification = NotificationCompat.Builder(context, FALLBACK_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
        Log.d(TAG, "Posted fallback notification: $message")
    }
}
