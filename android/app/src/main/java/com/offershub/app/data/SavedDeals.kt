package com.offershub.app.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Deals the user saved (♥), kept on the phone in full, so they stay after the deal has left
 * the live feed.
 */
class SavedDeals private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("saved_deals", Context.MODE_PRIVATE)

    private val _deals = MutableStateFlow(load())
    /** Most recently saved first. */
    val deals: StateFlow<List<Offer>> = _deals.asStateFlow()

    fun isSaved(id: String): Boolean = _deals.value.any { it.id == id }

    fun toggle(offer: Offer) {
        val current = _deals.value
        val next = if (current.any { it.id == offer.id }) current.filterNot { it.id == offer.id }
        else listOf(offer) + current
        _deals.value = next
        prefs.edit { putString(KEY, JSONArray(next.map { it.toJson() }).toString()) }
    }

    private fun load(): List<Offer> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).toSavedOffer() }
    }.getOrDefault(emptyList())

    companion object {
        private const val KEY = "deals"

        @Volatile private var instance: SavedDeals? = null

        fun get(context: Context): SavedDeals =
            instance ?: synchronized(this) {
                instance ?: SavedDeals(context.applicationContext).also { instance = it }
            }
    }
}
