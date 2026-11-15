package com.tickers.app.ui.trades

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tickers.app.container
import com.tickers.app.portfolio.PortfolioData
import com.tickers.app.portfolio.PortfolioRepository
import com.tickers.app.portfolio.TradeSide
import com.tickers.app.ui.Format
import com.tickers.app.ui.common.EmptyState
import com.tickers.app.ui.theme.changeColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TradesViewModel(repo: PortfolioRepository) : ViewModel() {
    val data: StateFlow<PortfolioData?> = repo.observePortfolio()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TradesScreen(onAdd: () -> Unit, onOpen: (Long) -> Unit) {
    val context = LocalContext.current.applicationContext
    val vm: TradesViewModel = viewModel { TradesViewModel(context.container.portfolio) }
    val data by vm.data.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Trades") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = "Add trade") }
        },
    ) { padding ->
        val d = data ?: return@Scaffold
        if (d.trades.isEmpty()) {
            Column(Modifier.padding(padding)) {
                EmptyState("No trades yet", "Every buy and sell you add shows up here.", "Add trade", onAdd)
            }
            return@Scaffold
        }
        val salesById = remember(d) { d.assets.flatMap { it.sales }.associateBy { it.sell.id } }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(d.trades.reversed(), key = { it.id }) { t ->
                val isBuy = t.side == TradeSide.BUY
                val sale = salesById[t.id]
                ListItem(
                    modifier = Modifier.clickable { onOpen(t.id) },
                    leadingContent = {
                        Text(
                            if (isBuy) "BUY" else "SELL",
                            color = changeColor(if (isBuy) 1.0 else -1.0),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    headlineContent = { Text("${Format.qty(t.quantity, t.symbol)} @ ${Format.price(t.price)}") },
                    supportingContent = {
                        val parts = listOfNotNull(
                            dateFormat.format(Date(t.executedAt)),
                            if (t.fee > 0) "fee ${Format.usd(t.fee)}" else null,
                            if (!isBuy && !t.autoAllocate) "lots chosen" else null,
                            t.note.takeIf { it.isNotBlank() },
                        )
                        Text(parts.joinToString(" · "), maxLines = 2)
                    },
                    trailingContent = {
                        Column(horizontalAlignment = Alignment.End) {
                            if (isBuy) {
                                Text(Format.usd(t.quantity * t.price + t.fee), style = MaterialTheme.typography.titleSmall)
                                Text("paid", style = MaterialTheme.typography.bodySmall)
                            } else if (sale != null) {
                                Text(Format.signedUsd(sale.pnl), color = changeColor(sale.pnl), style = MaterialTheme.typography.titleSmall)
                                Text(Format.signedPct(sale.pnlPct), color = changeColor(sale.pnl), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
