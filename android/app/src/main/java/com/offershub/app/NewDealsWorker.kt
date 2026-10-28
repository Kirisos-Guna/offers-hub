package com.offershub.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.offershub.app.data.AppSettings
import com.offershub.app.data.StaticFeed
import java.util.concurrent.TimeUnit

/**
 * Fallback alerts for builds without Firebase: checks the static feed every 15 minutes
 * (Android's minimum). Firebase builds get instant push messages instead.
 */
class NewDealsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = AppSettings.get(applicationContext)
        val feed = try {
            StaticFeed().fetch()
        } catch (_: Exception) {
            return Result.retry()
        }
        val lastSeen = settings.lastSeenPostedAt
        val fresh = feed.filter { it.postedAt > lastSeen }
        feed.maxOfOrNull { it.postedAt }?.let { if (it > lastSeen) settings.lastSeenPostedAt = it }
        // The first run only records where we are, so the user isn't flooded with old deals.
        if (lastSeen == 0L || AppVisibility.foreground) return Result.success()
        fresh.filter { settings.wantsNotification(it.discount, it.title, it.text, it.expired) }.take(5).forEach { o ->
            DealNotifier.show(
                applicationContext,
                DealAlert(o.id, o.title, o.store, o.price, o.mrp, o.discount, o.image, o.url),
            )
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewDealsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("new-deals", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
