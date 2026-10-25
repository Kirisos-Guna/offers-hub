package com.offershub.app

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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.offershub.app.data.OffersApi
import com.offershub.app.data.Prefs
import com.offershub.app.ui.formatPrice
import java.util.concurrent.TimeUnit

/** Checks the feed in the background (every 15 min, Android's minimum) and notifies about new deals. */
class NewDealsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        val feed = try {
            OffersApi(prefs.serverUrl).feed()
        } catch (_: Exception) {
            return Result.retry()
        }
        val lastSeen = prefs.lastSeenPostedAt
        val fresh = feed.filter { it.postedAt > lastSeen }
        feed.maxOfOrNull { it.postedAt }?.let { if (it > lastSeen) prefs.lastSeenPostedAt = it }
        // First run only records where we are, so the user isn't flooded with old deals.
        if (lastSeen == 0L || fresh.isEmpty() || !canNotify()) return Result.success()

        val lines = fresh.take(6).map { o ->
            o.price?.let { "${o.title} · ${formatPrice(it)}" } ?: o.title
        }
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (fresh.size == 1) "New deal" else "${fresh.size} new deals")
            .setContentText(lines.first())
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach(style::addLine) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
        return Result.success()
    }

    private fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val CHANNEL_ID = "new_deals"
        private const val NOTIFICATION_ID = 1

        fun schedule(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "New deals", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val request = PeriodicWorkRequestBuilder<NewDealsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("new-deals", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
