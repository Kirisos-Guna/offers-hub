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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offershub.app.data.AppSettings
import com.offershub.app.data.Offer
import com.offershub.app.data.SortOrder
import com.offershub.app.ui.theme.LiveGreen
import com.offershub.app.ui.theme.storeColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Root of the UI: the deal feed, with settings as a second screen. */
@Composable
fun OffersApp(vm: OffersViewModel, settings: AppSettings) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    val update by vm.update.collectAsStateWithLifecycle()

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(
            settings,
            dealCount = vm.state.collectAsStateWithLifecycle().value.all.size,
            update = update,
            installedVersion = vm.updater.installedVersion(),
            onCheckUpdate = { vm.checkForUpdate(manual = true) },
            onOpenUpdate = { showUpdate = true },
            onBack = { showSettings = false; vm.clearUpdateMessage() },
        )
    } else {
        HomeScreen(vm, onOpenSettings = { showSettings = true }, onOpenUpdate = { showUpdate = true })
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
fun HomeScreen(vm: OffersViewModel, onOpenSettings: () -> Unit, onOpenUpdate: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Offer?>(null) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // A tapped notification opens its deal once the feed has it.
    LaunchedEffect(state.pendingOpenId, state.all) {
        val id = state.pendingOpenId ?: return@LaunchedEffect
        state.all.firstOrNull { it.id == id }?.let {
            selected = it
            vm.consumePendingOpen()
        }
    }
    LaunchedEffect(gridState) {
        snapshotFlow { gridState.firstVisibleItemIndex == 0 && gridState.firstVisibleItemScrollOffset < 40 }
            .distinctUntilChanged()
            .collect(vm::setAtTop)
    }
    // Stay pinned to the newest deal while the user is at the top.
    LaunchedEffect(state.offers.firstOrNull()?.id) {
        if (state.unseenNew == 0 && gridState.firstVisibleItemIndex <= 1) gridState.scrollToItem(0)
    }
    LaunchedEffect(state.selectedStore, state.sort, state.query) { gridState.scrollToItem(0) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = {
                        Column {
                            Text("Offers Hub", style = MaterialTheme.typography.headlineSmall)
                            StatusLine(state.status, state.all.size)
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
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
                state.loading -> LoadingGrid()
                state.status == FeedStatus.Error && state.all.isEmpty() ->
                    MessageState("Couldn't load deals", state.error ?: "Check your connection.", "Try again", vm::refresh)
                state.offers.isEmpty() -> {
                    val filtered = state.query.isNotBlank() || state.selectedStore != null
                    MessageState(
                        if (filtered) "No deals match" else "No deals yet",
                        if (filtered) "Try another search or store." else "New deals appear here the moment they're posted.",
                        if (filtered) "Clear filters" else null,
                    ) { vm.setQuery(""); vm.selectStore(null) }
                }
                else -> LazyVerticalGrid(
                    // 140dp keeps two columns on phones even at a large display size.
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    state = gridState,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.offers, key = { it.id }) { offer ->
                        OfferCard(offer, onClick = { selected = offer }, modifier = Modifier.animateItem())
                    }
                }
            }

            AnimatedVisibility(
                visible = state.unseenNew > 0,
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
                            if (state.unseenNew == 1) "1 new deal" else "${state.unseenNew} new deals",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }
    }

    selected?.let { offer -> OfferDetailSheet(offer, onDismiss = { selected = null }) }
}

@Composable
private fun StatusLine(status: FeedStatus, count: Int) {
    val (color, text) = when (status) {
        FeedStatus.Live -> LiveGreen to "Live · $count deals"
        FeedStatus.Offline -> Color(0xFFE09B23) to "Offline · showing saved deals"
        FeedStatus.Connecting -> MaterialTheme.colorScheme.outline to "Connecting…"
        FeedStatus.Error -> MaterialTheme.colorScheme.error to "Can't reach the server"
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
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Search deals, brands, products", maxLines = 1) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(50),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
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
private fun FilterRow(state: OffersUiState, onStore: (String?) -> Unit, onSort: (SortOrder) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box {
                FilterChip(
                    selected = state.sort != SortOrder.Latest,
                    onClick = { sortMenu = true },
                    label = { Text(state.sort.label) },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOrder.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label) },
                            leadingIcon = { if (sort == state.sort) Icon(Icons.Filled.Check, null) },
                            onClick = { sortMenu = false; onSort(sort) },
                        )
                    }
                }
            }
        }
        item {
            FilterChip(selected = state.selectedStore == null, onClick = { onStore(null) }, label = { Text("All") })
        }
        items(state.stores, key = { it.name }) { store ->
            FilterChip(
                selected = state.selectedStore == store.name,
                onClick = { onStore(if (state.selectedStore == store.name) null else store.name) },
                label = { Text("${if (store.name == "Other") "More" else store.name} · ${store.count}") },
                leadingIcon = {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(storeColor(store.name)))
                },
                colors = FilterChipDefaults.filterChipColors(),
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
                Text(title, style = MaterialTheme.typography.titleLarge)
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
