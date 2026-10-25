package com.offershub.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offershub.app.data.Offer
import com.offershub.app.data.OffersApi
import com.offershub.app.data.Prefs
import com.offershub.app.data.SortOrder
import com.offershub.app.data.Store
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OffersUiState(
    val all: List<Offer> = emptyList(),
    val selectedStore: String? = null,
    val query: String = "",
    val sort: SortOrder = SortOrder.Latest,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val serverUrl: String = "",
) {
    val stores: List<Store> =
        all.groupingBy { it.store }.eachCount().map { Store(it.key, it.value) }.sortedByDescending { it.count }

    val offers: List<Offer> = run {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val filtered = all.filter { o ->
            (selectedStore == null || o.store == selectedStore) &&
                words.all { w -> o.title.lowercase().contains(w) || o.text.lowercase().contains(w) }
        }
        when (sort) {
            SortOrder.Latest -> filtered
            SortOrder.Discount -> filtered.sortedByDescending { it.discount ?: -1 }
            SortOrder.Price -> filtered.sortedWith(compareBy(nullsLast()) { it.price })
        }
    }
}

class OffersViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    var api = OffersApi(prefs.serverUrl)
        private set

    private val _state = MutableStateFlow(OffersUiState(serverUrl = api.baseUrl))
    val state: StateFlow<OffersUiState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        reload(showSpinner = true)
    }

    fun refresh() = reload(showSpinner = false)

    /** Quiet refresh used by the auto-refresh timer: no spinner, keeps old data on failure. */
    fun refreshSilently() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            runCatching { api.feed() }.onSuccess { feed -> show(feed) }
        }
    }

    fun selectStore(store: String?) = _state.update { it.copy(selectedStore = store) }

    fun setSort(sort: SortOrder) = _state.update { it.copy(sort = sort) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setServerUrl(url: String) {
        prefs.serverUrl = url
        api = OffersApi(prefs.serverUrl)
        _state.update { OffersUiState(serverUrl = api.baseUrl) }
        reload(showSpinner = true)
    }

    /** Throws when the feed can't be read; used by the settings dialog's test button. */
    suspend fun testServer(url: String) {
        OffersApi(url).feed()
    }

    private fun show(feed: List<Offer>) {
        _state.update { it.copy(all = feed, loading = false, refreshing = false, error = null) }
        // Whatever is on screen no longer needs a notification.
        feed.maxOfOrNull { it.postedAt }?.let { if (it > prefs.lastSeenPostedAt) prefs.lastSeenPostedAt = it }
    }

    private fun reload(showSpinner: Boolean) {
        loadJob?.cancel()
        _state.update { it.copy(loading = showSpinner && it.all.isEmpty(), refreshing = !showSpinner, error = null) }
        loadJob = viewModelScope.launch {
            try {
                show(api.feed())
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, refreshing = false,
                        error = "Can't reach ${api.baseUrl}\n${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }
}
