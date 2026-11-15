package com.tickers.app.ui.trades

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tickers.app.container
import com.tickers.app.data.AssetType
import com.tickers.app.data.MarketRepository
import com.tickers.app.portfolio.Allocation
import com.tickers.app.portfolio.EPS
import com.tickers.app.portfolio.OpenLot
import com.tickers.app.portfolio.PortfolioCalculator
import com.tickers.app.portfolio.PortfolioCalculator.parseDecimal
import com.tickers.app.portfolio.PortfolioException
import com.tickers.app.portfolio.PortfolioRepository
import com.tickers.app.portfolio.Trade
import com.tickers.app.portfolio.TradeSide
import com.tickers.app.ui.Format
import com.tickers.app.ui.common.SectionTitle
import com.tickers.app.ui.theme.changeColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import kotlin.math.min

data class TradeForm(
    val loaded: Boolean = false,
    val side: TradeSide = TradeSide.BUY,
    val symbol: String = "ETH-USD",
    val quantity: String = "",
    val price: String = "",
    val fee: String = "0",
    val executedAt: Long = System.currentTimeMillis(),
    val note: String = "",
    val autoAllocate: Boolean = true,
    /** Manual lot picks: buy id -> quantity text. */
    val manualLots: Map<Long, String> = emptyMap(),
    val availableLots: List<OpenLot> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
)

class EditTradeViewModel(
    private val repo: PortfolioRepository,
    private val market: MarketRepository,
    val tradeId: Long,
) : ViewModel() {

    private val _form = MutableStateFlow(TradeForm())
    val form = _form.asStateFlow()

    /** Symbols offered in the picker: traded + watchlist crypto, ETH first. */
    private val _symbols = MutableStateFlow(listOf("ETH-USD"))
    val symbols = _symbols.asStateFlow()

    init {
        viewModelScope.launch {
            val watch = market.watchlist().filter { it.type == AssetType.CRYPTO }.map { it.symbol }
            _symbols.value = (listOf("ETH-USD") + repo.symbols() + watch).distinct()

            val existing = if (tradeId != 0L) repo.trade(tradeId) else null
            if (existing != null) {
                val manual = if (existing.side == TradeSide.SELL && !existing.autoAllocate) {
                    repo.allocationsOf(existing.id).associate { it.buyId to Format.plain(it.quantity) }
                } else emptyMap()
                _form.value = TradeForm(
                    loaded = true,
                    side = existing.side,
                    symbol = existing.symbol,
                    quantity = Format.plain(existing.quantity),
                    price = Format.plain(existing.price),
                    fee = Format.plain(existing.fee),
                    executedAt = existing.executedAt,
                    note = existing.note,
                    autoAllocate = existing.autoAllocate,
                    manualLots = manual,
                )
            } else {
                runCatching { market.refresh("ETH-USD") }
                val price = market.quote("ETH-USD")?.price
                _form.value = TradeForm(loaded = true, price = price?.let { "%.2f".format(Locale.US, it) } ?: "")
            }
            reloadLots()
        }
    }

    fun update(change: TradeForm.() -> TradeForm) {
        val before = _form.value
        _form.update { it.change().copy(error = null) }
        val after = _form.value
        if (after.side == TradeSide.SELL &&
            (before.side != after.side || before.symbol != after.symbol || before.executedAt != after.executedAt)
        ) viewModelScope.launch { reloadLots() }
    }

    fun setSymbol(symbol: String) = viewModelScope.launch {
        update { copy(symbol = symbol) }
        if (tradeId == 0L) {
            runCatching { market.refresh(symbol) }
            market.quote(symbol)?.price?.let { p -> update { copy(price = "%.2f".format(Locale.US, p)) } }
        }
    }

    /** Switching to manual lots: start from what FIFO would pick. */
    fun setAuto(auto: Boolean) {
        val f = _form.value
        val picks = if (!auto && f.manualLots.isEmpty()) {
            fifoPicks(f.availableLots, parseDecimal(f.quantity) ?: 0.0).associate { it.first.buy.id to Format.plain(it.second) }
        } else f.manualLots
        update { copy(autoAllocate = auto, manualLots = picks) }
    }

    private suspend fun reloadLots() {
        val f = _form.value
        if (f.side != TradeSide.SELL) return
        val lots = repo.lotsAvailableFor(f.symbol, f.executedAt, tradeId)
        _form.update { it.copy(availableLots = lots) }
    }

    fun save(onDone: () -> Unit) {
        val f = _form.value
        val qty = parseDecimal(f.quantity)
        val price = parseDecimal(f.price)
        val fee = parseDecimal(f.fee.ifBlank { "0" })
        val error = when {
            qty == null || qty <= 0 -> "Enter a quantity greater than 0"
            price == null || price <= 0 -> "Enter a price greater than 0"
            fee == null || fee < 0 -> "Fee must be 0 or more"
            f.side == TradeSide.SELL && !f.autoAllocate &&
                kotlin.math.abs(manualTotal(f) - qty) > EPS ->
                "Chosen positions add up to ${Format.plain(manualTotal(f))}, not ${Format.plain(qty)}"
            else -> null
        }
        if (error != null) {
            _form.update { it.copy(error = error) }
            return
        }
        val trade = Trade(
            id = tradeId, symbol = f.symbol, side = f.side, quantity = qty!!, price = price!!, fee = fee!!,
            executedAt = f.executedAt, note = f.note.trim(),
            autoAllocate = f.side == TradeSide.BUY || f.autoAllocate,
        )
        val lots = if (f.side == TradeSide.SELL && !f.autoAllocate) {
            f.manualLots.mapNotNull { (buyId, text) ->
                parseDecimal(text)?.takeIf { it > EPS }?.let { Allocation(tradeId, buyId, it) }
            }
        } else emptyList()
        _form.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                repo.save(trade, lots)
                runCatching { market.refresh(f.symbol) }
                onDone()
            } catch (e: PortfolioException) {
                _form.update { it.copy(saving = false, error = e.message) }
            }
        }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        try {
            repo.delete(tradeId)
            onDone()
        } catch (e: PortfolioException) {
            _form.update { it.copy(error = "Can't delete: ${e.message}. Delete or edit the sells that use it first.") }
        }
    }

    companion object {
        fun manualTotal(f: TradeForm) = f.manualLots.values.sumOf { parseDecimal(it) ?: 0.0 }

        fun fifoPicks(lots: List<OpenLot>, quantity: Double): List<Pair<OpenLot, Double>> {
            var need = quantity
            return lots.mapNotNull { lot ->
                if (need <= EPS) return@mapNotNull null
                val take = min(lot.remaining, need)
                need -= take
                lot to take
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTradeScreen(tradeId: Long, onDone: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val vm: EditTradeViewModel = viewModel(key = "trade-$tradeId") {
        EditTradeViewModel(context.container.portfolio, context.container.repository, tradeId)
    }
    val f by vm.form.collectAsStateWithLifecycle()
    val symbols by vm.symbols.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (tradeId == 0L) "Add trade" else "Edit trade") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (tradeId != 0L) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                },
            )
        },
    ) { padding ->
        if (!f.loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TradeSide.entries.forEachIndexed { i, side ->
                    SegmentedButton(
                        selected = f.side == side,
                        onClick = { vm.update { copy(side = side) } },
                        shape = SegmentedButtonDefaults.itemShape(i, TradeSide.entries.size),
                    ) { Text(if (side == TradeSide.BUY) "Buy" else "Sell") }
                }
            }

            SymbolPicker(f.symbol, symbols, onPick = { vm.setSymbol(it) })

            val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            val asset = Format.ticker(f.symbol)
            OutlinedTextField(
                f.quantity, { v -> vm.update { copy(quantity = v) } },
                label = { Text(if (f.side == TradeSide.BUY) "Quantity received ($asset)" else "Quantity sold ($asset)") },
                singleLine = true, keyboardOptions = decimal, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                f.price, { v -> vm.update { copy(price = v) } },
                label = { Text("Price per $asset (USD / USDC)") },
                singleLine = true, keyboardOptions = decimal, modifier = Modifier.fillMaxWidth(),
            )
            val qty = parseDecimal(f.quantity)
            val price = parseDecimal(f.price)
            val fee = parseDecimal(f.fee) ?: 0.0
            val orderValue = if (qty != null && price != null) qty * price else null
            OutlinedTextField(
                f.fee, { v -> vm.update { copy(fee = v) } },
                label = { Text("Fee (USD), as shown by Revolut X") },
                supportingText = {
                    orderValue?.takeIf { it > 0 }?.let {
                        Text("Order ${Format.usd(it)} · fee = ${"%.3f".format(fee / it * 100)}%")
                    }
                },
                singleLine = true, keyboardOptions = decimal, modifier = Modifier.fillMaxWidth(),
            )
            DateTimeField(f.executedAt, onChange = { ms -> vm.update { copy(executedAt = ms) } })
            OutlinedTextField(
                f.note, { v -> vm.update { copy(note = v) } },
                label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(),
            )

            if (f.side == TradeSide.SELL) SellLots(vm, f, qty, price, fee)

            f.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { vm.save(onDone) }, enabled = !f.saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (tradeId == 0L) "Add ${if (f.side == TradeSide.BUY) "buy" else "sell"}" else "Save")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this trade?") },
            text = { Text("Profit figures are recalculated without it.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(onDone) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SellLots(vm: EditTradeViewModel, f: TradeForm, qty: Double?, price: Double?, fee: Double) {
    SectionTitle("Which positions are sold")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Oldest first (FIFO)", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (f.autoAllocate) "Recalculated automatically if you edit earlier trades"
                else "You choose how much comes from each position",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = f.autoAllocate, onCheckedChange = { vm.setAuto(it) })
    }
    val owned = f.availableLots.sumOf { it.remaining }
    Text(
        "Owned at that time: ${Format.qty(owned, f.symbol)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val picks: List<Pair<OpenLot, Double>> = if (f.autoAllocate) {
        EditTradeViewModel.fifoPicks(f.availableLots, qty ?: 0.0)
    } else {
        f.availableLots.map { it to (parseDecimal(f.manualLots[it.buy.id] ?: "") ?: 0.0) }
    }
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    f.availableLots.forEach { lot ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${Format.qty(lot.remaining, f.symbol)} @ ${Format.price(lot.buy.price)}")
                Text(dateFormat.format(Date(lot.buy.executedAt)), style = MaterialTheme.typography.bodySmall)
            }
            if (f.autoAllocate) {
                val take = picks.firstOrNull { it.first.buy.id == lot.buy.id }?.second ?: 0.0
                Text(if (take > EPS) Format.plain(take) else "—")
            } else {
                OutlinedTextField(
                    f.manualLots[lot.buy.id] ?: "",
                    { v -> vm.update { copy(manualLots = manualLots + (lot.buy.id to v)) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(0.6f),
                    placeholder = { Text("0") },
                )
            }
        }
    }
    // Preview of the realized result of this sell.
    if (price != null && qty != null && qty > 0) {
        val cost = picks.sumOf { (lot, take) -> take * lot.unitCost }
        val proceeds = qty * price - fee
        val pnl = proceeds - cost
        Text(
            "Realized on this sale: ${Format.signedUsd(pnl)} (${Format.signedPct(if (cost > 0) pnl / cost * 100 else 0.0)})",
            color = changeColor(pnl),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun SymbolPicker(symbol: String, symbols: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Asset: ${Format.ticker(symbol)}", modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            symbols.forEach { s ->
                DropdownMenuItem(text = { Text(Format.ticker(s)) }, onClick = { open = false; onPick(s) })
            }
        }
    }
}

/** Date + time in the phone's time zone (the date picker itself works in UTC). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeField(millis: Long, onChange: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val local = Instant.ofEpochMilli(millis).atZone(zone)
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val label = remember(millis) { SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(millis)) }
    val time = remember(millis) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis)) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.weight(1f)) { Text(label) }
        OutlinedButton(onClick = { pickTime = true }) { Text(time) }
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = local.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utcMidnight ->
                        val date = Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate()
                        onChange(ZonedDateTime.of(date, local.toLocalTime(), zone).toInstant().toEpochMilli())
                    }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }

    if (pickTime) {
        val state = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickTime = false },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val t = LocalTime.of(state.hour, state.minute)
                    onChange(ZonedDateTime.of(local.toLocalDate(), t, zone).toInstant().toEpochMilli())
                    pickTime = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
        )
    }
}
