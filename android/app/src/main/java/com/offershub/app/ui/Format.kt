package com.offershub.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.core.net.toUri
import com.offershub.app.data.Offer
import java.text.NumberFormat
import java.util.Locale

private val rupees = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
    maximumFractionDigits = 0
}

fun formatPrice(value: Double): String = rupees.format(value)

fun timeAgo(epochSeconds: Long): String {
    val ageMs = System.currentTimeMillis() - epochSeconds * 1000
    if (ageMs < DateUtils.MINUTE_IN_MILLIS) return "Just now"
    return DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

/** Posted in the last 15 minutes. */
fun Offer.isNew(): Boolean = System.currentTimeMillis() / 1000 - postedAt < 15 * 60

/** The post's prefix ("GRAB", "Loot"), unless it's just the discount the badge already shows. */
val Offer.displayTag: String?
    get() = tag?.takeIf { t -> t.any { it.isLetter() } && !t.contains('%') }

/** Opens the deal; Android hands Amazon/Flipkart links to the shop's app when it's installed. */
fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

fun shareOffer(context: Context, offer: Offer) {
    val price = offer.price?.let { " at ${formatPrice(it)}" } ?: ""
    val text = "${offer.title}$price\n${offer.url}"
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share deal"))
}
