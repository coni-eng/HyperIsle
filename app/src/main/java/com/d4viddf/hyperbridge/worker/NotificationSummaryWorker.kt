package com.d4viddf.hyperbridge.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.d4viddf.hyperbridge.MainActivity
import com.d4viddf.hyperbridge.R
import com.d4viddf.hyperbridge.data.AppPreferences
import com.d4viddf.hyperbridge.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import java.util.Calendar
import java.util.concurrent.TimeUnit

class NotificationSummaryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "notification_summary_daily"
        private const val CHANNEL_ID = "hyper_isle_summary_channel"
        private const val NOTIFICATION_ID = 999999

        fun schedule(context: Context, summaryHour: Int) {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, summaryHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            val initialDelay = target.timeInMillis - now.timeInMillis

            val workRequest = PeriodicWorkRequestBuilder<NotificationSummaryWorker>(
                24, TimeUnit.HOURS
            )
                .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        val preferences = AppPreferences(applicationContext)
        val database = AppDatabase.getDatabase(applicationContext)

        // Check if summary is still enabled
        val summaryEnabled = preferences.summaryEnabledFlow.first()
        if (!summaryEnabled) {
            return Result.success()
        }

        // Get items from last 24 hours
        val since = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(24)
        val items = database.digestDao().getItemsSince(since)

        if (items.isEmpty()) {
            // Clean up old items anyway
            database.digestDao().deleteOlderThan(since)
            return Result.success()
        }

        // Group by package name and count
        val grouped = items.groupBy { it.packageName }
        val summary = grouped.entries
            .sortedByDescending { it.value.size }
            .take(5)
            .joinToString(", ") { entry ->
                val appName = getAppName(entry.key)
                "$appName ${entry.value.size}"
            }

        val totalCount = items.size
        val title = applicationContext.getString(R.string.summary_notification_title)
        val text = applicationContext.getString(R.string.summary_notification_text, totalCount, summary)

        // Create notification channel
        createSummaryChannel()

        // Create intent to open summary screen
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_summary", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Post notification
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.`ic_launcher_foreground`)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) 
            == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        }

        // Clean up old items (keep last 7 days for history)
        val cleanupThreshold = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        database.digestDao().deleteOlderThan(cleanupThreshold)

        return Result.success()
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName.substringAfterLast(".")
        }
    }

    private fun createSummaryChannel() {
        val name = applicationContext.getString(R.string.channel_summary)
        val channel = NotificationChannel(
            CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
