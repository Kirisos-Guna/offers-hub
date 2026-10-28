package com.offershub.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offershub.app.data.AppUpdate
import com.offershub.app.data.FeedEvent
import com.offershub.app.data.Updater
import java.io.File
import com.offershub.app.data.FeedSource
import com.offershub.app.data.Offer
import com.offershub.app.data.SortOrder
import com.offershub.app.data.Store
import kotlinx.coroutines.Job
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
    val all: List<Offer> = emptyList(),
    val selectedStore: String? = null,
    val query: String = "",
    val sort: SortOrder = SortOrder.Latest,
    val status: FeedStatus = FeedStatus.Connecting,
    val error: String? = null,
    val refreshing: Boolean = false,
    /** Deals that arrived while the user was scrolled away from the top. */
    val unseenNew: Int = 0,
    /** A deal to open as soon as it is in the feed (from a tapped notification). */
    val pendingOpenId: String? = null,
) {
    val loading: Boolean get() = all.isEmpty() && status == FeedStatus.Connecting

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
    private val source = FeedSource.create(app)
    private val _state = MutableStateFlow(OffersUiState())
    val state: StateFlow<OffersUiState> = _state.asStateFlow()

    private var feedJob: Job? = null
    private var atTop = true

    val updater = Updater(app)
    private val _update = MutableStateFlow<UpdateState>(UpdateState.None)
    val update: StateFlow<UpdateState> = _update.asStateFlow()
    private var updateJob: Job? = null

    init {
        subscribe()
        checkForUpdate(manual = false)
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
                if (manual) UpdateState.Failed(e.message ?: "Couldn't check for updates") else UpdateState.None
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
                UpdateState.Failed(e.message ?: "Download failed")
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
        _state.update { it.copy(refreshing = true) }
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

    fun openWhenAvailable(id: String) = _state.update { it.copy(pendingOpenId = id) }

    fun consumePendingOpen() = _state.update { it.copy(pendingOpenId = null) }

    private fun subscribe() {
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            source.feed().collect { event ->
                when (event) {
                    is FeedEvent.Data -> _state.update { s ->
                        val previous = s.all.maxOfOrNull { it.postedAt } ?: Long.MAX_VALUE
                        val arrived = event.offers.count { it.postedAt > previous }
                        s.copy(
                            all = event.offers,
                            status = if (event.live) FeedStatus.Live else FeedStatus.Offline,
                            error = null,
                            refreshing = false,
                            unseenNew = if (atTop) 0 else s.unseenNew + arrived,
                        )
                    }
                    is FeedEvent.Failed -> _state.update {
                        it.copy(status = if (it.all.isEmpty()) FeedStatus.Error else FeedStatus.Offline,
                            error = event.message, refreshing = false)
                    }
                }
            }
        }
    }
}
