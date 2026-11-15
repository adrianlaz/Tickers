package com.tickers.app.ui.search

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tickers.app.container
import com.tickers.app.data.MarketRepository
import com.tickers.app.data.SearchResult
import com.tickers.app.widget.Widgets
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val repo: MarketRepository,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchState())
    val state = _state.asStateFlow()
    private val queries = MutableStateFlow("")

    init {
        viewModelScope.launch {
            queries.debounce(350).distinctUntilChanged().collectLatest { q ->
                if (q.isBlank()) {
                    _state.value = _state.value.copy(loading = false, results = emptyList(), error = null)
                    return@collectLatest
                }
                _state.value = _state.value.copy(loading = true, error = null)
                _state.value = runCatching { repo.search(q.trim()) }.fold(
                    onSuccess = { _state.value.copy(loading = false, results = it) },
                    onFailure = { _state.value.copy(loading = false, error = "Search failed: ${it.message}") },
                )
            }
        }
    }

    fun onQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        queries.value = q
    }

    fun add(result: SearchResult, onDone: () -> Unit) = viewModelScope.launch {
        repo.add(result)
        Widgets.updateAll(appContext)
        onDone()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(onDone: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val vm: SearchViewModel = viewModel { SearchViewModel(context.container.repository, context) }
    val state by vm.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add asset") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::onQuery,
                label = { Text("Search: Apple, SOL, ^GSPC, …") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).focusRequester(focus),
            )
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.results, key = { it.symbol }) { r ->
                    ListItem(
                        headlineContent = { Text(r.symbol) },
                        supportingContent = { Text(r.name, maxLines = 1) },
                        trailingContent = { Text(listOf(r.type.name, r.exchange).filter { it.isNotBlank() }.joinToString(" · ")) },
                        modifier = Modifier.clickable { vm.add(r, onDone) },
                    )
                }
            }
        }
    }
}
