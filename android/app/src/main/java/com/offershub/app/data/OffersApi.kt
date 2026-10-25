package com.offershub.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the deals feed: offers.json on the GitHub Pages site (published by the sync workflow)
 * or on a local backend/server.py - both serve the same file.
 */
class OffersApi(baseUrl: String) {
    val baseUrl: String = baseUrl.trim().trimEnd('/')

    /** All current deals, newest first. */
    suspend fun feed(): List<Offer> {
        // The query string skips GitHub Pages' 10-minute cache so new deals show up right away.
        val json = JSONObject(get("/offers.json?t=${System.currentTimeMillis()}"))
        val items = json.getJSONArray("items")
        return (0 until items.length()).map { items.getJSONObject(it).toOffer() }
    }

    /** Server images are relative ("/media/x.jpg"); others are full URLs. */
    fun imageUrl(image: String?): String? = when {
        image == null -> null
        image.startsWith("/") -> baseUrl + image
        else -> image
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 20000
        try {
            if (conn.responseCode !in 200..299) throw IOException("Server returned ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
