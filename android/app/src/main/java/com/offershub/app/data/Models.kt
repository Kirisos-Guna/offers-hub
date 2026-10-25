package com.offershub.app.data

import org.json.JSONArray
import org.json.JSONObject

data class OfferLink(val url: String, val label: String)

data class Offer(
    val id: Long,
    val source: String,
    val title: String,
    val tag: String?,
    val store: String,
    val price: Double?,
    val mrp: Double?,
    val discount: Int?,
    val image: String?,
    val url: String,
    val links: List<OfferLink>,
    val text: String,
    val postedAt: Long,
)

data class Store(val name: String, val count: Int)

enum class SortOrder(val label: String) {
    Latest("Latest"),
    Discount("Top discount"),
    Price("Lowest price"),
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key)

private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)

internal fun JSONObject.toOffer(): Offer {
    val linksJson = optJSONArray("links") ?: JSONArray()
    val links = (0 until linksJson.length()).map {
        val l = linksJson.getJSONObject(it)
        OfferLink(l.getString("url"), l.optString("label"))
    }
    return Offer(
        id = getLong("id"),
        source = optString("source"),
        title = optString("title"),
        tag = optStringOrNull("tag"),
        store = optString("store", "Other"),
        price = optDoubleOrNull("price"),
        mrp = optDoubleOrNull("mrp"),
        discount = optIntOrNull("discount"),
        image = optStringOrNull("image"),
        url = getString("url"),
        links = links,
        text = optString("text"),
        postedAt = optLong("posted_at"),
    )
}
