package com.tickers.app.ui.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tickers.app.container
import com.tickers.app.portfolio.AssetSummary
import com.tickers.app.portfolio.EquityPoint
import com.tickers.app.portfolio.PortfolioCalculator
import com.tickers.app.portfolio.PortfolioData
import com.tickers.app.portfolio.PortfolioRepository
import com.tickers.app.ui.Format
import com.tickers.app.ui.common.EmptyState
import com.tickers.app.ui.common.SectionTitle
import com.tickers.app.ui.common.StatGrid
import com.tickers.app.ui.theme.changeColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PortfolioViewModel(val repo: PortfolioRepository) : ViewModel() {
    val data: StateFlow<PortfolioData?> = repo.observePortfolio()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        try {
            repo.refreshPrices()
        } finally {
            _refreshing.value = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(onAddTrade: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val vm: PortfolioViewModel = viewModel { PortfolioViewModel(context.container.portfolio) }
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var simulate by remember { mutableStateOf<AssetSummary?>(null) }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Portfolio") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddTrade) { Icon(Icons.Default.Add, contentDescription = "Add trade") }
        },
    ) { padding ->
        val d = data
        when {
            d == null -> Unit
            d.trades.isEmpty() -> Column(Modifier.padding(padding)) {
                EmptyState(
                    title = "No trades yet",
                    body = "Add your Revolut X buys and sells to track profit per position, realized and unrealized.",
                    action = "Add trade",
                    onAction = onAddTrade,
                )
            }
            else -> PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    item { TotalsCard(d) }
                    d.assets.forEach { asset ->
                        item(key = "asset-${asset.symbol}") {
                            AssetSection(vm.repo, d, asset, onSimulate = { simulate = asset })
                        }
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    simulate?.let { asset ->
        SimulateSaleSheet(asset = asset, onDismiss = { simulate = null })
    }
}

@Composable
private fun TotalsCard(d: PortfolioData) {
    Card(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Value", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(Format.usd(d.value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "${Format.signedUsd(d.totalPnl)}  (${Format.signedPct(d.roiPct)})  total",
                color = changeColor(d.totalPnl),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            StatGrid(
                listOf(
                    Triple("Unrealized", Format.signedUsd(d.unrealized), changeColor(d.unrealized)),
                    Triple("Realized", Format.signedUsd(d.realized), changeColor(d.realized)),
                    Triple("Invested (open)", Format.usd(d.openCost), Color.Unspecified),
                    Triple("Total bought", Format.usd(d.totalBought), Color.Unspecified),
                )
            )
        }
    }
}

@Composable
private fun AssetSection(repo: PortfolioRepository, d: PortfolioData, a: AssetSummary, onSimulate: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    Column {
        SectionTitle("${Format.ticker(a.symbol)} · ${Format.price(a.price)}")
        StatGrid(
            listOf(
                Triple("Holdings", Format.qty(a.openQuantity, a.symbol), Color.Unspecified),
                Triple("Value", Format.usd(a.value), Color.Unspecified),
                Triple("Unrealized", "${Format.signedUsd(a.unrealized)} (${Format.signedPct(a.unrealizedPct)})", changeColor(a.unrealized)),
                Triple("Realized", Format.signedUsd(a.realized), changeColor(a.realized)),
                Triple("Avg buy price", Format.price(a.avgBuyPrice), Color.Unspecified),
                Triple("Break-even", Format.price(a.breakEven), Color.Unspecified),
                Triple("ROI", Format.signedPct(a.roiPct), changeColor(a.roiPct)),
                Triple("Fees paid", Format.usd(a.feesPaid), Color.Unspecified),
                Triple("Trades", "${a.trades.size} (${a.sales.size} sells)", Color.Unspecified),
                Triple("Win rate", a.winRatePct?.let { "%.0f%%".format(Locale.US, it) } ?: "—", Color.Unspecified),
                Triple("Best sale", Format.signedUsd(a.bestSale?.pnl), changeColor(a.bestSale?.pnl)),
                Triple("Worst sale", Format.signedUsd(a.worstSale?.pnl), changeColor(a.worstSale?.pnl)),
                Triple("Avg holding", a.avgHoldingDays?.let { "%.1f days".format(Locale.US, it) } ?: "—", Color.Unspecified),
            )
        )

        // Equity curve: value vs. cost basis since the first trade.
        val trades = remember(d.trades, a.symbol) { d.trades.filter { it.symbol == a.symbol } }
        val curve by produceState<List<EquityPoint>?>(null, trades, d.allocations) {
            value = runCatching {
                val closes = repo.dailyHistory(a.symbol, trades.minOf { it.executedAt })
                PortfolioCalculator.equityCurve(trades, d.allocations, closes)
            }.getOrDefault(emptyList())
        }
        val valueColor = MaterialTheme.colorScheme.primary
        val costColor = MaterialTheme.colorScheme.outline
        curve?.takeIf { it.size >= 2 }?.let { points ->
            SectionTitle("Value vs. cost")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend("Value", valueColor)
                Legend("Cost basis", costColor)
            }
            EquityChart(points, valueColor, costColor)
        }

        SectionTitle("Open positions")
        if (a.openLots.isEmpty()) {
            Text("Everything sold.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        a.openLots.forEach { lot ->
            ListItem(
                headlineContent = { Text("${Format.qty(lot.remaining, a.symbol)} @ ${Format.price(lot.buy.price)}") },
                supportingContent = {
                    Text("${dateFormat.format(Date(lot.buy.executedAt))} · cost ${Format.usd(lot.cost)}")
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Format.signedUsd(lot.pnl), color = changeColor(lot.pnl), style = MaterialTheme.typography.titleSmall)
                        Text(Format.signedPct(lot.pnlPct), color = changeColor(lot.pnl), style = MaterialTheme.typography.bodySmall)
                    }
                },
            )
            HorizontalDivider()
        }
        if (a.openLots.isNotEmpty()) {
            OutlinedButton(onClick = onSimulate, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("Simulate sale")
            }
        }
    }
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(Modifier.size(10.dp), shape = CircleShape, color = color) {}
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
