package com.offershub.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offershub.app.R
import com.offershub.app.data.AppSettings
import com.offershub.app.data.Offer
import com.offershub.app.data.SortOrder
import com.offershub.app.ui.theme.LiveGreen
import com.offershub.app.ui.theme.storeColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Root of the UI: the deal feed, with settings as a second screen. */
@Composable
fun OffersApp(vm: OffersViewModel, settings: AppSettings) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    val update by vm.update.collectAsStateWithLifecycle()
    // Kept here so coming back from Settings returns to the same place in the list.
    val gridState = rememberLazyGridState()

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            settings,
            update = update,
            installedVersion = vm.updater.installedVersion(),
            onCheckUpdate = { vm.checkForUpdate(manual = true) },
            onOpenUpdate = { showUpdate = true },
            onBack = { showSettings = false; vm.clearUpdateMessage() },
        )
    } else {
        HomeScreen(vm, gridState, onOpenSettings = { showSettings = true }, onOpenUpdate = { showUpdate = true })
    }

    val inProgress = update is UpdateState.Downloading || update is UpdateState.Ready
    if (showUpdate || inProgress) {
        if (update is UpdateState.Available || inProgress || (showUpdate && update is UpdateState.Failed)) {
            UpdateDialog(
                state = update,
                onDownload = vm::downloadUpdate,
                onInstall = vm::installUpdate,
                onClose = { showUpdate = false; vm.dismissUpdate(); vm.clearUpdateMessage() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: OffersViewModel, gridState: LazyGridState, onOpenSettings: () -> Unit, onOpenUpdate: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val scope = rememberCoroutineScope()
    val showHot = !state.showSaved && !state.filtering && state.sort == SortOrder.Latest && state.hot.isNotEmpty()

    if (state.showSaved) BackHandler { vm.setShowSaved(false) }

    // A tapped notification or widget row opens its deal once the feed has it.
    LaunchedEffect(state.pendingOpenId, state.all, state.fetchedDeal) {
        val id = state.pendingOpenId ?: return@LaunchedEffect
        if (state.all.any { it.id == id } || state.fetchedDeal?.id == id) {
            selectedId = id
            vm.consumePendingOpen()
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset < 40 }
            .distinctUntilChanged()
            .collect(vm::setAtTop)
    }
    // Load older deals near the end of the list. Under a filter the list can be short with nothing
    // scrolled, so loading is left to the effect below and the "load more" button.
    LaunchedEffect(gridState, state.offers.size, state.filtering) {
        if (state.filtering) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last >= gridState.layoutInfo.totalItemsCount - 8) vm.loadMore() }
    }
    // A filter that matches few of the loaded deals pulls in a few more pages, once typing stops.
    LaunchedEffect(state.query, state.selectedStore, state.selectedCategory) {
        delay(500)
        vm.loadMoreForFilter()
    }
    // Stay pinned to the newest deal while the user is at the top.
    LaunchedEffect(state.offers.firstOrNull()?.id) {
        if (state.unseenNew == 0 && gridState.firstVisibleItemIndex <= 1) gridState.scrollToItem(0)
    }
    // Back to the top when the list changes, but not when returning from Settings.
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(state.selectedStore, state.selectedCategory, state.sort, state.query, state.showSaved) }
            .drop(1)
            .collect { gridState.scrollToItem(0) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    navigationIcon = {
                        if (state.showSaved) {
                            IconButton(onClick = { vm.setShowSaved(false) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back))
                            }
                        }
                    },
                    title = {
                        if (state.showSaved) {
                            Text(stringResource(R.string.saved_title), style = MaterialTheme.typography.headlineSmall)
                        } else {
                            Column {
                                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                                StatusLine(state.status)
                            }
                        }
                    },
                    actions = {
                        if (!state.showSaved) {
                            IconButton(onClick = { vm.setShowSaved(true) }) {
                                Icon(
                                    if (state.saved.isEmpty()) Icons.Filled.FavoriteBorder else Icons.Filled.Favorite,
                                    contentDescription = stringResource(R.string.cd_saved),
                                )
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.cd_settings))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                    scrollBehavior = scrollBehavior,
                )
                (update as? UpdateState.Available)?.takeUnless { it.dismissed }?.let {
                    UpdateBanner(it.update.version, onUpdate = onOpenUpdate, onDismiss = vm::dismissUpdate)
                }
                SearchField(state.query, vm::setQuery)
                CategoryRow(state, vm::selectCategory)
                FilterRow(state, vm::selectStore, vm::setSort)
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            when {
                state.loading && !state.showSaved -> LoadingGrid()
                state.status == FeedStatus.Error && state.all.isEmpty() && !state.showSaved ->
                    MessageState(stringResource(R.string.error_title), stringResource(R.string.error_body),
                        stringResource(R.string.try_again), vm::refresh)
                state.offers.isEmpty() && state.showSaved && !state.filtering ->
                    MessageState(stringResource(R.string.saved_empty_title), stringResource(R.string.saved_empty_body), null) {}
                state.offers.isEmpty() && state.filtering && (state.endReached || state.showSaved) ->
                    MessageState(stringResource(R.string.no_match_title), stringResource(R.string.no_match_body),
                        stringResource(R.string.clear_filters)) {
                        vm.setQuery(""); vm.selectStore(null); vm.selectCategory(null)
                    }
                state.offers.isEmpty() && !state.filtering ->
                    MessageState(stringResource(R.string.no_deals_title), stringResource(R.string.no_deals_body), null) {}
                else -> LazyVerticalGrid(
                    // 140dp keeps two columns on phones even at a large display size.
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    state = gridState,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (showHot) {
                        item(key = "hot", span = { GridItemSpan(maxLineSpan) }) {
                            HotRow(state.hot, onOpen = { selectedId = it.id })
                        }
                    }
                    items(state.offers, key = { it.id }) { offer ->
                        OfferCard(
                            offer,
                            saved = offer.id in state.savedIds,
                            onToggleSave = { vm.toggleSaved(offer) },
                            onClick = { selectedId = offer.id },
                            modifier = Modifier.animateItem(),
                        )
                    }
                    if (state.loadingMore) {
                        item(key = "more", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(28.dp))
                            }
                        }
                    } else if (state.filtering && !state.showSaved && !state.endReached) {
                        item(key = "load_more", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(8.dp), Alignment.Center) {
                                OutlinedButton(onClick = vm::loadMore) { Text(stringResource(R.string.load_more)) }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = state.unseenNew > 0 && !state.showSaved,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            ) {
                Surface(
                    onClick = { scope.launch { gridState.animateScrollToItem(0) } },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shadowElevation = 6.dp,
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.size(18.dp))
                        Text(
                            pluralStringResource(R.plurals.new_deals_count, state.unseenNew, state.unseenNew),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }

    // Looked up by id so the sheet follows live changes (e.g. the deal being marked as ended).
    val selected = selectedId?.let { id ->
        state.all.firstOrNull { it.id == id } ?: state.saved.firstOrNull { it.id == id }
            ?: state.fetchedDeal?.takeIf { it.id == id }
    }
    selected?.let { offer ->
        OfferDetailSheet(
            offer,
            saved = offer.id in state.savedIds,
            onToggleSave = { vm.toggleSaved(offer) },
            onDismiss = { selectedId = null },
        )
    }
}

@Composable
private fun HotRow(deals: List<Offer>, onOpen: (Offer) -> Unit) {
    Column(Modifier.padding(bottom = 4.dp)) {
        Text(
            stringResource(R.string.hot_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(deals, key = { "hot_" + it.id }) { HotCard(it, onClick = { onOpen(it) }) }
        }
    }
}

@Composable
private fun StatusLine(status: FeedStatus) {
    val (color, text) = when (status) {
        FeedStatus.Live -> LiveGreen to stringResource(R.string.status_live)
        FeedStatus.Offline -> Color(0xFFE09B23) to stringResource(R.string.status_offline)
        FeedStatus.Connecting -> MaterialTheme.colorScheme.outline to stringResource(R.string.status_connecting)
        FeedStatus.Error -> MaterialTheme.colorScheme.error to stringResource(R.string.status_error)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.cd_clear_search))
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(50),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun CategoryRow(state: OffersUiState, onCategory: (String?) -> Unit) {
    if (state.categories.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.categories, key = { "cat_" + it.name }) { cat ->
            val selected = state.selectedCategory == cat.name
            FilterChip(
                selected = selected,
                onClick = { onCategory(if (selected) null else cat.name) },
                label = { Text("${categoryEmoji(cat.name)} ${categoryName(cat.name)}", maxLines = 1) },
            )
        }
    }
}

@Composable
private fun FilterRow(state: OffersUiState, onStore: (String?) -> Unit, onSort: (SortOrder) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    val sortLabels = mapOf(
        SortOrder.Latest to stringResource(R.string.sort_latest),
        SortOrder.Discount to stringResource(R.string.sort_discount),
        SortOrder.Price to stringResource(R.string.sort_price),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box {
                FilterChip(
                    selected = state.sort != SortOrder.Latest,
                    onClick = { sortMenu = true },
                    label = { Text(sortLabels.getValue(state.sort), maxLines = 1) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOrder.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sortLabels.getValue(sort)) },
                            leadingIcon = { if (sort == state.sort) Icon(Icons.Filled.Check, null) },
                            onClick = { sortMenu = false; onSort(sort) },
                        )
                    }
                }
            }
        }
        item {
            FilterChip(
                selected = state.selectedStore == null,
                onClick = { onStore(null) },
                label = { Text(stringResource(R.string.filter_all), maxLines = 1) },
            )
        }
        items(state.stores, key = { it.name }) { store ->
            FilterChip(
                selected = state.selectedStore == store.name,
                onClick = { onStore(if (state.selectedStore == store.name) null else store.name) },
                label = {
                    Text(if (store.name == "Other") stringResource(R.string.filter_more) else store.name, maxLines = 1)
                },
                leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(storeColor(store.name))) },
            )
        }
    }
}

/** Card-shaped placeholders that pulse while the first deals load. */
@Composable
private fun LoadingGrid() {
    val alpha by rememberInfiniteTransition(label = "loading").animateFloat(
        initialValue = 0.35f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "alpha",
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 140.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(List(8) { it }) {
            Column(
                Modifier
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(6.dp)
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium)
                        .alpha(alpha).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
                Spacer(Modifier.height(10.dp))
                repeat(3) { line ->
                    Box(
                        Modifier.fillMaxWidth(if (line == 2) 0.5f else 0.9f).height(12.dp)
                            .padding(horizontal = 6.dp).clip(RoundedCornerShape(6.dp))
                            .alpha(alpha).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

/** Scrollable so pull-to-refresh still works on empty and error screens. */
@Composable
private fun MessageState(title: String, body: String, action: String?, onAction: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        item {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (action != null) Button(onClick = onAction) { Text(action) }
            }
        }
    }
}
