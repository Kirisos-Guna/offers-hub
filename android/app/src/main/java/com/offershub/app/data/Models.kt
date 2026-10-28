package com.offershub.app.data

import org.json.JSONArray
import org.json.JSONObject

data class OfferLink(val url: String, val label: String)

data class Offer(
    val id: String,
    val source: String,
    val title: String,
    val tag: String?,
    val store: String,
    val price: Double?,
    val mrp: Double?,
    val discount: Int?,
    /** Absolute image URL, or null when the deal has none. */
    val image: String?,
    val url: String,
    val links: List<OfferLink>,
    val text: String,
    /** Unix seconds. */
    val postedAt: Long,
    /** One of [Categories.ALL], or "Other". */
    val category: String = "Other",
    /** The channel edited the post to say the deal is over. */
    val expired: Boolean = false,
) {
    /** How much cheaper than MRP, when both are known. */
    val savings: Double?
        get() = if (price != null && mrp != null && mrp > price) mrp - price else null
}

data class Store(val name: String, val count: Int)

enum class SortOrder { Latest, Discount, Price }

/** Categories the listener assigns (backend/categories.py), in display order. */
object Categories {
    val ALL = listOf(
        "Electronics", "Fashion", "Home & Kitchen", "Beauty & Health", "Grocery",
        "Kids & Toys", "Sports & Fitness", "Car & Bike", "Cards & Offers",
    )
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

private fun JSONObject.optDoubleOrNull(key: String): Double? = if (isNull(key)) null else optDouble(key)

private fun JSONObject.optIntOrNull(key: String): Int? = if (isNull(key)) null else optInt(key)

/** A deal from the static offers.json feed; its relative image paths resolve against [baseUrl]. */
internal fun JSONObject.toOffer(baseUrl: String): Offer {
    val linksJson = optJSONArray("links") ?: JSONArray()
    val links = (0 until linksJson.length()).map {
        val l = linksJson.getJSONObject(it)
        OfferLink(l.getString("url"), l.optString("label"))
    }
    val image = optStringOrNull("image")
    return Offer(
        id = optString("source_key", optString("id")).replace(':', '_'),
        source = optString("source"),
        title = optString("title"),
        tag = optStringOrNull("tag"),
        store = optString("store", "Other"),
        price = optDoubleOrNull("price"),
        mrp = optDoubleOrNull("mrp"),
        discount = optIntOrNull("discount"),
        image = if (image != null && image.startsWith("/")) baseUrl + image else image,
        url = getString("url"),
        links = links,
        text = optString("text"),
        postedAt = optLong("posted_at"),
        category = optString("category", "Other"),
        expired = optBoolean("expired", false),
    )
}

/** For saving deals on the phone (and the widget's cache). */
internal fun Offer.toJson(): JSONObject = JSONObject()
    .put("id", id).put("source", source).put("title", title).put("tag", tag).put("store", store)
    .put("price", price).put("mrp", mrp).put("discount", discount).put("image", image).put("url", url)
    .put("links", JSONArray(links.map { JSONObject().put("url", it.url).put("label", it.label) }))
    .put("text", text).put("posted_at", postedAt).put("category", category).put("expired", expired)

internal fun JSONObject.toSavedOffer(): Offer = toOffer(baseUrl = "").copy(id = getString("id"))

/** A deal from a Firestore `offers` document (written by backend/firebase_publisher.py). */
internal fun firestoreOffer(id: String, data: Map<String, Any?>): Offer? {
    val url = data["url"] as? String ?: return null
    @Suppress("UNCHECKED_CAST")
    val links = (data["links"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { l ->
        (l["url"] as? String)?.let { OfferLink(it, l["label"] as? String ?: "") }
    }
    return Offer(
        id = id,
        source = data["source"] as? String ?: "",
        title = data["title"] as? String ?: "",
        tag = (data["tag"] as? String)?.takeIf { it.isNotBlank() },
        store = data["store"] as? String ?: "Other",
        price = (data["price"] as? Number)?.toDouble(),
        mrp = (data["mrp"] as? Number)?.toDouble(),
        discount = (data["discount"] as? Number)?.toInt(),
        image = (data["image"] as? String)?.takeIf { it.isNotBlank() },
        url = url,
        links = links.ifEmpty { listOf(OfferLink(url, "")) },
        text = data["text"] as? String ?: "",
        postedAt = (data["posted_at"] as? Number)?.toLong() ?: 0L,
        category = data["category"] as? String ?: "Other",
        expired = data["expired"] as? Boolean ?: false,
    )
}
