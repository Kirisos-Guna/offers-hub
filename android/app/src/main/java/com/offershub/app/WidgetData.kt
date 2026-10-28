package com.offershub.app

import android.content.Context
import androidx.core.content.edit
import androidx.glance.appwidget.updateAll
import com.offershub.app.data.Offer
import com.offershub.app.data.OfferLink
import com.offershub.app.data.toJson
import com.offershub.app.data.toSavedOffer
import com.offershub.app.widget.DealsWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray

/** The latest deals for the home-screen widget, kept on the phone so it works with the app closed. */
object WidgetData {
    private const val PREFS = "widget"
    private const val KEY = "latest"
    private const val MAX = 8
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(context: Context): List<Offer> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).toSavedOffer() }
    }.getOrDefault(emptyList())

    /** From the app's live feed. */
    fun setLatest(context: Context, offers: List<Offer>) {
        val latest = offers.filterNot { it.expired }.take(MAX)
        if (latest.map { it.id } == load(context).map { it.id }) return
        save(context, latest)
    }

    /** From a push message while the app is closed. */
    fun addLatest(context: Context, data: Map<String, String>) {
        val id = data["id"] ?: return
        val url = data["url"] ?: return
        val offer = Offer(
            id = id, source = "", title = data["title"].orEmpty(), tag = null, store = data["store"].orEmpty(),
            price = data["price"]?.toDoubleOrNull(), mrp = data["mrp"]?.toDoubleOrNull(),
            discount = data["discount"]?.toIntOrNull(), image = data["image"]?.takeIf { it.isNotBlank() },
            url = url, links = listOf(OfferLink(url, "")), text = data["text"].orEmpty(),
            postedAt = data["posted_at"]?.toLongOrNull() ?: (System.currentTimeMillis() / 1000),
            category = data["category"] ?: "Other",
        )
        save(context, (listOf(offer) + load(context).filterNot { it.id == id }).take(MAX))
    }

    private fun save(context: Context, offers: List<Offer>) {
        prefs(context).edit { putString(KEY, JSONArray(offers.map { it.toJson() }).toString()) }
        val app = context.applicationContext
        scope.launch { runCatching { DealsWidget().updateAll(app) } }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
