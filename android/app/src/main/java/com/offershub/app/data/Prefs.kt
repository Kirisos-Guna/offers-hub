package com.offershub.app.data

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("offers_hub", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, null) ?: DEFAULT_SERVER
        set(value) = prefs.edit { putString(KEY_SERVER, value.trim().trimEnd('/')) }

    /** posted_at of the newest deal the user has been shown or notified about. */
    var lastSeenPostedAt: Long
        get() = prefs.getLong(KEY_LAST_SEEN, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_SEEN, value) }

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_LAST_SEEN = "last_seen_posted_at"

        /** GitHub Pages site the sync workflow publishes to. */
        const val DEFAULT_SERVER = "https://kirisos-guna.github.io/offers-hub"
    }
}
