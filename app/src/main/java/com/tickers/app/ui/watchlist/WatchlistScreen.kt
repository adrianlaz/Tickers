package com.tickers.app.ui.watchlist

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tickers.app.container
import com.tickers.app.data.MarketRepository
import com.tickers.app.data.db.WatchItem
import com.tickers.app.ui.Format
import com.tickers.app.ui.theme.changeColor
import com.tickers.app.ui.update.UpdateBanner
import com.tickers.app.widget.Widgets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WatchlistViewModel(
    private val repo: MarketRepository,
    private val appContext: Context,
) : ViewModel() {

    val items: StateFlow<List<WatchItem>> = repo.observeWatchlist()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                repo.refreshAll()
                Widgets.updateAll(appContext)
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun remove(symbol: String) = viewModelScope.launch {
        repo.remove(symbol)
        Widgets.updateAll(appContext)
    }

    fun move(from: Int, to: Int) = viewModelScope.launch {
        val symbols = items.value.map { it.symbol }.toMutableList()
        if (to !in symbols.indices) return@launch
        symbols.add(to, symbols.removeAt(from))
        repo.reorder(symbols)
        Widgets.updateAll(appContext)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val vm: WatchlistViewModel = viewModel { WatchlistViewModel(context.container.repository, context) }
    val updater = remember { context.container.updater }
    val items by vm.items.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Markets") },
                actions = {
                    IconButton(onClick = { editing = !editing }) {
                        Icon(
                            if (editing) Icons.Default.Check else Icons.Default.Edit,
                            contentDescription = if (editing) "Done" else "Edit list",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "Add asset")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            UpdateBanner(updater)
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(items, key = { _, it -> it.symbol }) { index, item ->
                        WatchRow(
                            item = item,
                            editing = editing,
                            onClick = { onOpen(item.symbol) },
                            onUp = { vm.move(index, index - 1) },
                            onDown = { vm.move(index, index + 1) },
                            onDelete = { vm.remove(item.symbol) },
                        )
                        HorizontalDivider()
                    }
                    item(key = "spacer") { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }
}

@Composable
private fun WatchRow(
    item: WatchItem,
    editing: Boolean,
    onClick: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = !editing, onClick = onClick),
        headlineContent = { Text(Format.ticker(item.symbol), fontWeight = FontWeight.Bold) },
        supportingContent = { Text(item.name, maxLines = 1) },
        trailingContent = {
            if (editing) {
                Row {
                    IconButton(onClick = onUp) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                    IconButton(onClick = onDown) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                    IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Remove") }
                }
            } else {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                    Text(
                        Format.price(item.price, item.currency),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        Format.change(item.changePct),
                        color = changeColor(item.changePct),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
    )
}
