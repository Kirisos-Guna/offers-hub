package com.offershub.app.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface FeedEvent {
    /** [live] = straight from the server (not a cached copy while offline). */
    data class Data(val offers: List<Offer>, val live: Boolean) : FeedEvent
    data class Failed(val message: String) : FeedEvent
}

interface FeedSource {
    /** The newest deals; emits again whenever they change. */
    fun feed(): Flow<FeedEvent>

    /** Up to [limit] deals posted up to [beforePostedAt], newest first (scrolling further). */
    suspend fun older(beforePostedAt: Long, limit: Int): List<Offer>

    /** One deal by id, however old (a tapped notification), or null if it is gone. */
    suspend fun offer(id: String): Offer?

    companion object {
        /** Firestore (instant) when the app was built with Firebase settings, else the static feed. */
        fun create(context: Context): FeedSource =
            if (FirebaseApp.getApps(context).isNotEmpty()) FirestoreFeed() else StaticFeed()
    }
}

/**
 * Live feed: the listener writes each deal to Firestore the moment it's posted. Only the newest
 * page is watched live; older deals load page by page as the user scrolls. Every document read
 * counts against the free plan's 50,000 reads a day, so pages stay small.
 */
class FirestoreFeed : FeedSource {
    private val offers = FirebaseFirestore.getInstance().collection("offers")

    override fun feed(): Flow<FeedEvent> = callbackFlow {
        val registration = offers
            .orderBy("posted_at", Query.Direction.DESCENDING)
            .limit(LIVE_PAGE)
            // Metadata changes too: when the server confirms the cached deals unchanged, that is
            // the only event that turns "Offline · saved deals" into "Live".
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
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

    override suspend fun older(beforePostedAt: Long, limit: Int): List<Offer> =
        suspendCancellableCoroutine { cont ->
            offers.orderBy("posted_at", Query.Direction.DESCENDING)
                // "Or equal": another deal can share the last one's second; duplicates are merged.
                .whereLessThanOrEqualTo("posted_at", beforePostedAt)
                .limit(limit.toLong())
                .get()
                .addOnSuccessListener { snap ->
                    cont.resume(snap.documents.mapNotNull { d -> d.data?.let { firestoreOffer(d.id, it) } })
                }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    override suspend fun offer(id: String): Offer? = suspendCancellableCoroutine { cont ->
        offers.document(id).get()
            .addOnSuccessListener { doc -> cont.resume(doc.data?.let { firestoreOffer(doc.id, it) }) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private companion object {
        const val LIVE_PAGE = 60L
    }
}

/** Fallback for builds without Firebase: polls the published offers.json (all deals at once). */
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

    override suspend fun older(beforePostedAt: Long, limit: Int): List<Offer> = emptyList()

    override suspend fun offer(id: String): Offer? = fetch().firstOrNull { it.id == id }

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
