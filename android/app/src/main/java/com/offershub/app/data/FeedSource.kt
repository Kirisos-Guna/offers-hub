package com.offershub.app.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

sealed interface FeedEvent {
    /** [live] = straight from the server (not a cached copy while offline). */
    data class Data(val offers: List<Offer>, val live: Boolean) : FeedEvent
    data class Failed(val message: String) : FeedEvent
}

interface FeedSource {
    /** Current deals, newest first; emits again whenever they change. */
    fun feed(): Flow<FeedEvent>

    companion object {
        /** Firestore (instant) when the app was built with Firebase settings, else the static feed. */
        fun create(context: Context): FeedSource =
            if (FirebaseApp.getApps(context).isNotEmpty()) FirestoreFeed() else StaticFeed()
    }
}

/** Live feed: the listener writes each deal to Firestore the moment it's posted. */
class FirestoreFeed : FeedSource {
    override fun feed(): Flow<FeedEvent> = callbackFlow {
        val registration = FirebaseFirestore.getInstance()
            .collection("offers")
            .orderBy("posted_at", Query.Direction.DESCENDING)
            .limit(LIMIT)
            .addSnapshotListener { snapshot, error ->
                when {
                    error != null -> trySend(FeedEvent.Failed(error.message ?: "Couldn't load deals"))
                    snapshot != null -> trySend(
                        FeedEvent.Data(
                            snapshot.documents.mapNotNull { doc -> doc.data?.let { firestoreOffer(doc.id, it) } },
                            live = !snapshot.metadata.isFromCache,
                        )
                    )
                }
            }
        awaitClose { registration.remove() }
    }

    private companion object {
        const val LIMIT = 400L
    }
}

/** Fallback for builds without Firebase: polls the published offers.json. */
class StaticFeed(baseUrl: String = DEFAULT_URL) : FeedSource {
    private val baseUrl = baseUrl.trimEnd('/')

    override fun feed(): Flow<FeedEvent> = flow {
        while (true) {
            emit(
                try {
                    FeedEvent.Data(fetch(), live = true)
                } catch (e: Exception) {
                    FeedEvent.Failed(e.message ?: "Couldn't load deals")
                }
            )
            delay(POLL_MS)
        }
    }

    suspend fun fetch(): List<Offer> = withContext(Dispatchers.IO) {
        // The query string skips GitHub Pages' 10-minute cache.
        val conn = URL("$baseUrl/offers.json?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        try {
            if (conn.responseCode !in 200..299) throw IOException("Server returned ${conn.responseCode}")
            val items = JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getJSONArray("items")
            (0 until items.length()).map { items.getJSONObject(it).toOffer(baseUrl) }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_URL = "https://kirisos-guna.github.io/offers-hub"
        private const val POLL_MS = 60_000L
    }
}
