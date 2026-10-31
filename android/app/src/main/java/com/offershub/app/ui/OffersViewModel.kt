package com.offershub.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offershub.app.R
import com.offershub.app.WidgetData
import com.offershub.app.data.AppUpdate
import com.offershub.app.data.Categories
import com.offershub.app.data.FeedEvent
import com.offershub.app.data.FeedSource
import com.offershub.app.data.Offer
import com.offershub.app.data.SavedDeals
import com.offershub.app.data.SortOrder
import com.offershub.app.data.Store
import com.offershub.app.data.Updater
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FeedStatus { Connecting, Live, Offline, Error }

sealed interface UpdateState {
    data object None : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate, val dismissed: Boolean = false) : UpdateState
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateState
    data class Ready(val update: AppUpdate, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

data class OffersUiState(
    /** The live first page plus older pages loaded by scrolling, newest first. */
    val all: List<Offer> = emptyList(),
    val saved: List<Offer> = emptyList(),
    val showSaved: Boolean = false,
    val selectedStore: String? = null,
    val selectedCategory: String? = null,
    val query: String = "",
    val sort: SortOrder = SortOrder.Latest,
    val status: FeedStatus = FeedStatus.Connecting,
    val refreshing: Boolean = false,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    /** Deals that arrived while the user was scrolled away from the top. */
    val unseenNew: Int = 0,
    /** A deal to open as soon as it is in the feed (from a tapped notification or the widget). */
    val pendingOpenId: String? = null,
    /** A deal fetched on its own because it is older than the loaded pages. */
    val fetchedDeal: Offer? = null,
) {
    val loading: Boolean get() = all.isEmpty() && status == FeedStatus.Connecting

    private val base: List<Offer> get() = if (showSaved) saved else all

    val savedIds: Set<String> = saved.mapTo(HashSet()) { it.id }

    val filtering: Boolean
        get() = query.isNotBlank() || selectedStore != null || selectedCategory != null

    val stores: List<Store> =
        base.groupingBy { it.store }.eachCount().map { Store(it.key, it.value) }.sortedByDescending { it.count }

    /** Categories present in the current list, in their usual order. */
    val categories: List<Store> = run {
        val counts = base.groupingBy { it.category }.eachCount()
        Categories.ALL.mapNotNull { c -> counts[c]?.let { Store(c, it) } }
    }

    /** Biggest live discounts of the last few hours, for the "Hot right now" row. */
    val hot: List<Offer> = run {
        val since = System.currentTimeMillis() / 1000 - HOT_WINDOW_SECONDS
        // A real product with a price, not an "Upto 80% off on shoes" sale post.
        all.filter {
            !it.expired && it.price != null && it.postedAt >= since && (it.discount ?: 0) >= HOT_MIN_DISCOUNT
        }
            .sortedWith(compareByDescending<Offer> { it.image != null }.thenByDescending { it.discount })
            .take(10)
            .takeIf { it.size >= 3 }
            .orEmpty()
    }

    val offers: List<Offer> = run {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val filtered = base.filter { o ->
            (selectedStore == null || o.store == selectedStore) &&
                (selectedCategory == null || o.category == selectedCategory) &&
                words.all { w -> o.title.lowercase().contains(w) || o.text.lowercase().contains(w) }
        }
        when (sort) {
            SortOrder.Latest -> filtered
            SortOrder.Discount -> filtered.sortedByDescending { it.discount ?: -1 }
            SortOrder.Price -> filtered.sortedWith(compareBy(nullsLast()) { it.price })
        }
    }

    private companion object {
        const val HOT_WINDOW_SECONDS = 3 * 3600L
        const val HOT_MIN_DISCOUNT = 50
    }
}

class OffersViewModel(app: Application) : AndroidViewModel(app) {
    private val source = FeedSource.create(app)
    private val savedDeals = SavedDeals.get(app)
    private val _state = MutableStateFlow(OffersUiState())
    val state: StateFlow<OffersUiState> = _state.asStateFlow()

    private var feedJob: Job? = null
    private var moreJob: Job? = null
    private var atTop = true
    private var live: List<Offer> = emptyList()
    private var older: List<Offer> = emptyList()
    private var connectTimeout: Job? = null

    val updater = Updater(app)
    private val _update = MutableStateFlow<UpdateState>(UpdateState.None)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private var updateJob: Job? = null

    init {
        subscribe()
        checkForUpdate(manual = false)
        viewModelScope.launch { savedDeals.deals.collect { list -> _state.update { it.copy(saved = list) } } }
    }

    fun toggleSaved(offer: Offer) = savedDeals.toggle(offer)

    fun setShowSaved(show: Boolean) = _state.update { it.copy(showSaved = show) }

    fun selectCategory(category: String?) = _state.update { it.copy(selectedCategory = category) }

    /** Loads the next older page: on scrolling, or the "load more" button under a filter. */
    fun loadMore() {
        if (moreJob?.isActive == true) return
        moreJob = viewModelScope.launch { loadPage() }
    }

    /**
     * A search or filter only sees loaded deals, so when it matches few of them, a few more pages
     * are read. It stops there (the user can tap "load more"): reading on until the end would
     * spend the whole database on one search, and the free plan's daily reads are limited.
     */
    fun loadMoreForFilter() {
        moreJob?.cancel()
        moreJob = viewModelScope.launch {
            repeat(FILTER_AUTO_PAGES) {
                val s = _state.value
                if (!s.filtering || s.offers.size >= FILTER_ENOUGH) return@launch
                if (!loadPage()) return@launch
            }
        }
    }

    /** Returns false when there is nothing more to load. */
    private suspend fun loadPage(): Boolean {
        val s = _state.value
        if (s.showSaved || s.endReached || s.all.isEmpty()) return false
        _state.update { it.copy(loadingMore = true) }
        return try {
            val page = source.older(s.all.minOf { it.postedAt }, PAGE_SIZE)
            val known = s.all.mapTo(HashSet()) { it.id }
            older = older + page
            val end = page.size < PAGE_SIZE || page.all { it.id in known }
            publish(endReached = end)
            !end
        } catch (e: CancellationException) {
            _state.update { it.copy(loadingMore = false) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loadingMore = false) }
            false
        }
    }

    /** Merges the live page with older pages into the visible list. */
    private fun publish(endReached: Boolean? = null) {
        val merged = (live + older).distinctBy { it.id }.sortedByDescending { it.postedAt }
        _state.update { it.copy(all = merged, loadingMore = false, endReached = endReached ?: it.endReached) }
    }

    /** Looks for a newer GitHub release; [manual] also reports "up to date" and errors. */
    fun checkForUpdate(manual: Boolean) {
        if (updateJob?.isActive == true) return
        if (manual) _update.value = UpdateState.Checking
        updateJob = viewModelScope.launch {
            _update.value = try {
                updater.check()?.let { UpdateState.Available(it) }
                    ?: if (manual) UpdateState.UpToDate else UpdateState.None
            } catch (e: Exception) {
                if (manual) UpdateState.Failed(getApplication<Application>().getString(R.string.update_check_failed)) else UpdateState.None
            }
        }
    }

    fun downloadUpdate() {
        val update = when (val s = _update.value) {
            is UpdateState.Available -> s.update
            else -> return
        }
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            _update.value = UpdateState.Downloading(update, 0f)
            _update.value = try {
                val apk = updater.download(update) { p -> _update.value = UpdateState.Downloading(update, p) }
                UpdateState.Ready(update, apk)
            } catch (e: Exception) {
                UpdateState.Failed(getApplication<Application>().getString(R.string.download_failed))
            }
        }
    }

    fun installUpdate() {
        val ready = _update.value as? UpdateState.Ready ?: return
        updater.install(ready.apk)
    }

    /** Hides the banner for this session (the update stays available in Settings). */
    fun dismissUpdate() {
        _update.update { (it as? UpdateState.Available)?.copy(dismissed = true) ?: UpdateState.None }
    }

    fun clearUpdateMessage() {
        if (_update.value is UpdateState.UpToDate || _update.value is UpdateState.Failed) _update.value = UpdateState.None
    }

    fun refresh() {
        moreJob?.cancel()
        older = emptyList()
        _state.update {
            it.copy(refreshing = true, endReached = false, loadingMore = false,
                status = if (it.status == FeedStatus.Error) FeedStatus.Connecting else it.status)
        }
        subscribe()
    }

    fun selectStore(store: String?) = _state.update { it.copy(selectedStore = store) }

    fun setSort(sort: SortOrder) = _state.update { it.copy(sort = sort) }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    /** The grid reports whether its first row is visible; new deals there count as seen. */
    fun setAtTop(value: Boolean) {
        atTop = value
        if (value) _state.update { it.copy(unseenNew = 0) }
    }

    /** Opens a deal from a notification or the widget, fetching it if it isn't loaded. */
    fun openWhenAvailable(id: String) {
        _state.update { it.copy(pendingOpenId = id) }
        viewModelScope.launch {
            val found = runCatching { source.offer(id) }.getOrNull()
            _state.update { s ->
                when {
                    s.pendingOpenId != id -> s
                    found != null -> s.copy(fetchedDeal = found)
                    s.all.none { it.id == id } -> s.copy(pendingOpenId = null) // the deal is gone
                    else -> s
                }
            }
        }
    }

    fun consumePendingOpen() = _state.update { it.copy(pendingOpenId = null) }

    private companion object {
        const val PAGE_SIZE = 40
        const val FILTER_AUTO_PAGES = 3
        const val FILTER_ENOUGH = 20
        const val CONNECT_TIMEOUT_MS = 12_000L
    }

    private fun subscribe() {
        feedJob?.cancel()
        // Firestore answers from its cache first, so "not live yet" isn't "offline" until the
        // server has had time to answer.
        connectTimeout?.cancel()
        connectTimeout = viewModelScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            _state.update { s ->
                if (s.status != FeedStatus.Connecting) s
                else s.copy(status = if (s.all.isEmpty()) FeedStatus.Error else FeedStatus.Offline)
            }
        }
        feedJob = viewModelScope.launch {
            source.feed().collect { event ->
                when (event) {
                    is FeedEvent.Data -> {
                        // An empty cache on first launch says nothing yet about the real feed.
                        if (event.offers.isEmpty() && !event.live) return@collect
                        val previous = live.maxOfOrNull { it.postedAt } ?: Long.MAX_VALUE
                        val arrived = event.offers.count { it.postedAt > previous }
                        // New deals push the oldest out of the live window; they stay in the list.
                        // (A deal missing from inside the window was deleted, so it goes.)
                        val ids = event.offers.mapTo(HashSet()) { it.id }
                        val windowStart = event.offers.minOfOrNull { it.postedAt }
                        if (windowStart != null) {
                            older = live.filter { it.id !in ids && it.postedAt <= windowStart } + older
                        }
                        live = event.offers
                        publish()
                        _state.update { s ->
                            s.copy(
                                status = when {
                                    event.live -> FeedStatus.Live
                                    s.status == FeedStatus.Live -> FeedStatus.Offline
                                    else -> s.status
                                },
                                refreshing = false,
                                unseenNew = if (atTop) 0 else s.unseenNew + arrived,
                            )
                        }
                        WidgetData.setLatest(getApplication(), event.offers)
                    }
                    is FeedEvent.Failed -> _state.update {
                        it.copy(status = if (it.all.isEmpty()) FeedStatus.Error else FeedStatus.Offline,
                            refreshing = false)
                    }
                }
            }
        }
    }
}
