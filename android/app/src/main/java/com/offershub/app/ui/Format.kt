package com.offershub.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.offershub.app.R
import com.offershub.app.data.Offer
import java.text.NumberFormat
import java.util.Locale

private val rupees = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).apply {
    maximumFractionDigits = 0
}

/** NumberFormat isn't thread-safe, and notifications format prices off the main thread. */
fun formatPrice(value: Double): String = synchronized(rupees) { rupees.format(value) }

/** "Just now", "5 min. ago", "2 hr. ago" in the app's language. */
fun timeAgo(context: Context, epochSeconds: Long): String {
    val ageMs = System.currentTimeMillis() - epochSeconds * 1000
    if (ageMs < DateUtils.MINUTE_IN_MILLIS) return context.getString(R.string.just_now)
    return DateUtils.getRelativeTimeSpanString(
        epochSeconds * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

@Composable
fun timeAgo(epochSeconds: Long): String = timeAgo(LocalContext.current, epochSeconds)

/** Posted in the last 15 minutes. */
fun Offer.isNew(): Boolean = !expired && System.currentTimeMillis() / 1000 - postedAt < 15 * 60

/** The post's prefix ("GRAB", "Loot"), unless it's just the discount the badge already shows. */
val Offer.displayTag: String?
    get() = tag?.takeIf { t -> t.any { it.isLetter() } && !t.contains('%') }

@StringRes
fun categoryLabel(category: String): Int? = when (category) {
    "Electronics" -> R.string.cat_electronics
    "Fashion" -> R.string.cat_fashion
    "Home & Kitchen" -> R.string.cat_home
    "Beauty & Health" -> R.string.cat_beauty
    "Grocery" -> R.string.cat_grocery
    "Kids & Toys" -> R.string.cat_kids
    "Sports & Fitness" -> R.string.cat_sports
    "Car & Bike" -> R.string.cat_auto
    "Cards & Offers" -> R.string.cat_cards
    else -> null
}

@Composable
fun categoryName(category: String): String = categoryLabel(category)?.let { stringResource(it) } ?: category

fun categoryEmoji(category: String): String = when (category) {
    "Electronics" -> "📱"
    "Fashion" -> "👕"
    "Home & Kitchen" -> "🏠"
    "Beauty & Health" -> "💄"
    "Grocery" -> "🛒"
    "Kids & Toys" -> "🧸"
    "Sports & Fitness" -> "⚽"
    "Car & Bike" -> "🏍️"
    "Cards & Offers" -> "💳"
    else -> "🏷️"
}

/** Opens the deal; Android hands Amazon/Flipkart links to the shop's app when it's installed. */
fun openLink(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_app, Toast.LENGTH_SHORT).show()
    }
}

fun shareOffer(context: Context, offer: Offer) {
    val price = offer.price?.let { " · ${formatPrice(it)}" } ?: ""
    val text = "${offer.title}$price\n${offer.url}"
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_deal)))
}
