package com.offershub.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offershub.app.data.Offer
import com.offershub.app.data.SortOrder

private const val AUTO_REFRESH_MS = 2 * 60 * 1000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: OffersViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Offer?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    // While the app is on screen, pick up deals posted since it was opened.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                vm.refreshSilently()
                delay(AUTO_REFRESH_MS)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Offers Hub", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Server settings")
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
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
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.error != null && state.offers.isEmpty() ->
                    ErrorState(state.error!!, onRetry = vm::refresh, onSettings = { showSettings = true })
                state.offers.isEmpty() -> EmptyState(state.query.isNotBlank() || state.selectedStore != null)
                else -> OfferGrid(state, vm, onOpen = { selected = it })
            }
        }
    }

    selected?.let { offer ->
        OfferDetailSheet(offer, vm.api.imageUrl(offer.image), onDismiss = { selected = null })
    }
    if (showSettings) {
        SettingsDialog(
            currentUrl = state.serverUrl,
            onTest = vm::testServer,
            onSave = { vm.setServerUrl(it); showSettings = false },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Search deals, brands, products") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = "Clear") }
            }
        },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun FilterRow(state: OffersUiState, onStore: (String?) -> Unit, onSort: (SortOrder) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Box {
                AssistChip(
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
            FilterChip(
                selected = state.selectedStore == null,
                onClick = { onStore(null) },
                label = { Text("All") },
            )
        }
        items(state.stores, key = { it.name }) { store ->
            FilterChip(
                selected = state.selectedStore == store.name,
                onClick = { onStore(if (state.selectedStore == store.name) null else store.name) },
                label = { Text("${store.name} · ${store.count}") },
            )
        }
    }
}

@Composable
private fun OfferGrid(state: OffersUiState, vm: OffersViewModel, onOpen: (Offer) -> Unit) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(state.selectedStore, state.sort, state.query) { gridState.scrollToItem(0) }

    LazyVerticalGrid(
        // 140dp keeps two columns on phones even with a larger display size (S24 at "Large" is
        // ~340dp wide); tablets and landscape get more.
        columns = GridCells.Adaptive(minSize = 140.dp),
        state = gridState,
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.offers, key = { it.id }) { offer ->
            OfferCard(offer, vm.api.imageUrl(offer.image), onClick = { onOpen(offer) })
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, onSettings: () -> Unit) {
    CenteredMessage {
        Text("Couldn't load deals", style = MaterialTheme.typography.titleMedium)
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onSettings) { Text("Server settings") }
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun EmptyState(filtered: Boolean) {
    CenteredMessage {
        Text(
            if (filtered) "No deals match" else "No deals yet",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (filtered) "Try another search or store." else "Pull down to refresh.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Scrollable so pull-to-refresh still works on empty and error screens. */
@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    androidx.compose.foundation.lazy.LazyColumn(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        item {
            Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}
