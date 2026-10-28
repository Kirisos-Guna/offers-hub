package com.offershub.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.offershub.app.ui.formatPrice
import kotlinx.coroutines.withTimeoutOrNull

/** What a new-deal notification shows; built from a push message or the fallback poller. */
data class DealAlert(
    val id: String,
    val title: String,
    val store: String,
    val price: Double?,
    val mrp: Double?,
    val discount: Int?,
    val image: String?,
    val url: String,
)

object DealNotifier {
    const val CHANNEL_ID = "new_deals"
    const val EXTRA_OFFER_ID = "offer_id"
    private const val GROUP = "deals"
    private const val SUMMARY_ID = 0

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.notif_channel_desc)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Shows one deal; call from a background thread (it downloads the image). */
    suspend fun show(context: Context, deal: DealAlert) {
        if (!canNotify(context)) return
        val notificationId = deal.id.hashCode()
        val open = PendingIntent.getActivity(
            context, notificationId,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OFFER_ID, deal.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val buy = PendingIntent.getActivity(
            context, notificationId + 1,
            Intent(Intent.ACTION_VIEW, deal.url.toUri()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val details = listOfNotNull(
            deal.price?.let { formatPrice(it) },
            deal.mrp?.takeIf { deal.price != null && it > deal.price }?.let { "MRP ${formatPrice(it)}" },
            deal.discount?.let { context.getString(R.string.percent_off, it) },
            deal.store.takeIf { it != "Other" },
        ).joinToString(" · ")

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(deal.title)
            .setContentText(details.ifEmpty { context.getString(R.string.notif_new_deal) })
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.notif_buy_now), buy)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP)
        val picture = deal.image?.let { loadBitmap(context, it) }
        if (picture != null) {
            builder.setLargeIcon(picture)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(picture).bigLargeIcon(null as Bitmap?))
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(details))
        }

        val manager = NotificationManagerCompat.from(context)
        try {
            manager.notify(notificationId, builder.build())
            // Keeps several deals together under one expandable group.
            manager.notify(
                SUMMARY_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(ContextCompat.getColor(context, R.color.notification_accent))
                    .setContentTitle(context.getString(R.string.notif_new_deals))
                    .setGroup(GROUP)
                    .setGroupSummary(true)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private suspend fun loadBitmap(context: Context, url: String): Bitmap? = withTimeoutOrNull(8_000) {
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).size(720).build()
        (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
    }
}
