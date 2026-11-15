package com.tickers.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.Zoom
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.DefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import com.tickers.app.container
import com.tickers.app.data.ChangeCalculator
import com.tickers.app.data.ChartRange
import com.tickers.app.data.MarketRepository
import com.tickers.app.data.PricePoint
import com.tickers.app.ui.Format
import com.tickers.app.ui.theme.changeColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class ChartState(
    val range: ChartRange = ChartRange.D1,
    val points: List<PricePoint> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class DetailViewModel(private val repo: MarketRepository, val symbol: String) : ViewModel() {

    val item = repo.observeWatchlist()
        .map { list -> list.firstOrNull { it.symbol == symbol } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _chart = MutableStateFlow(ChartState())
    val chart = _chart.asStateFlow()

    /** Fed to Vico; updated whenever new points arrive. */
    val modelProducer = CartesianChartModelProducer()

    init {
        load(ChartRange.D1)
    }

    private var loadJob: Job? = null

    fun load(range: ChartRange) {
        // Cancel an in-flight load so a slow earlier range can't overwrite a newer one.
        loadJob?.cancel()
        _chart.value = _chart.value.copy(range = range, loading = true, error = null)
        loadJob = viewModelScope.launch {
            val points = try {
                repo.chart(symbol, range)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _chart.value = _chart.value.copy(loading = false, error = e.message ?: "Failed to load")
                return@launch
            }
            if (points.size >= 2) {
                // Timestamps and range travel inside the model, so axis labels always match the
                // series being drawn, even mid-animation.
                modelProducer.runTransaction {
                    lineModel { series(points.map { it.price }) }
                    extras {
                        it[TimesKey] = points.map { p -> p.epochSec }
                        it[RangeKey] = range
                    }
                }
            }
            _chart.value = ChartState(range, points, loading = false)
        }
    }

    companion object {
        val TimesKey = ExtraStore.Key<List<Long>>()
        val RangeKey = ExtraStore.Key<ChartRange>()
    }
}

/** Fits the y-axis to the data (with 5% padding) instead of Vico's default of starting at 0. */
private object PaddedRange : CartesianLayerRangeProvider {
    private fun pad(minY: Double, maxY: Double) = max((maxY - minY) * 0.05, abs(maxY) * 0.0005)
    override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = minY - pad(minY, maxY)
    override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = maxY + pad(minY, maxY)
}

private fun ExtraStore.timeAt(x: Double): Date? =
    getOrNull(DetailViewModel.TimesKey)?.getOrNull(x.toInt())?.let { Date(it * 1000) }

private fun ExtraStore.format(x: Double, pattern: (ChartRange) -> String): String? {
    val range = getOrNull(DetailViewModel.RangeKey) ?: return null
    return timeAt(x)?.let { SimpleDateFormat(pattern(range), Locale.getDefault()).format(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(symbol: String, onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val vm: DetailViewModel = viewModel(key = symbol) {
        DetailViewModel(context.container.repository, symbol)
    }
    val item by vm.item.collectAsStateWithLifecycle()
    val chart by vm.chart.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.name ?: symbol, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(Format.ticker(symbol), style = MaterialTheme.typography.titleMedium)
            Text(
                Format.price(item?.price, item?.currency),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${Format.change(item?.changePct)}  24h",
                color = changeColor(item?.changePct),
                style = MaterialTheme.typography.titleMedium,
            )

            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChartRange.entries.forEach { r ->
                    FilterChip(
                        selected = r == chart.range,
                        onClick = { vm.load(r) },
                        label = { Text(r.label) },
                    )
                }
            }

            val rangeChange = chart.points.takeIf { it.size >= 2 }
                ?.let { ChangeCalculator.pct(it.last().price, it.first().price) }
            if (rangeChange != null && !chart.loading) {
                Text(
                    "${chart.range.label}: ${Format.change(rangeChange)}",
                    color = changeColor(rangeChange),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Box(Modifier.fillMaxWidth().height(320.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
                when {
                    chart.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(chart.error!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.load(chart.range) }) { Text("Retry") }
                    }
                    chart.points.size < 2 && chart.loading -> CircularProgressIndicator()
                    chart.points.size < 2 -> Text("No chart data for this range")
                    else -> PriceChart(vm.modelProducer, rangeChange ?: 0.0)
                }
                if (chart.loading && chart.points.size >= 2) CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun PriceChart(
    producer: CartesianChartModelProducer,
    rangeChange: Double,
) {
    val color = changeColor(rangeChange)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    // x is the point index, so nights/weekends without trading don't leave gaps.
    // Formatters read timestamps from the model's ExtraStore, never from captured UI state.
    val bottomFormatter = remember {
        CartesianValueFormatter { ctx, x, _ -> ctx.model.extraStore.format(x, ::axisPattern) ?: "" }
    }
    val startFormatter = remember {
        CartesianValueFormatter { _, y, _ -> Format.price(y).removePrefix("$") }
    }
    val markerFormatter = remember {
        DefaultCartesianMarker.ValueFormatter { ctx, targets ->
            val t = targets.firstOrNull() ?: return@ValueFormatter ""
            val y = (t as? LineCartesianLayerMarkerTarget)?.points?.firstOrNull()?.entry?.y
            listOfNotNull(Format.price(y), ctx.model.extraStore.format(t.x, ::markerPattern))
                .joinToString("  ·  ")
        }
    }
    val labelStyle = TextStyle(color = labelColor, fontSize = 11.sp)

    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(
                LineCartesianLayer.rememberLine(
                    fill = remember(color) { LineCartesianLayer.LineFill.single(Fill(color)) },
                    areaFill = remember(color) {
                        LineCartesianLayer.AreaFill.single(
                            Fill(Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0f))))
                        )
                    },
                )
            ),
            rangeProvider = PaddedRange,
        ),
        startAxis = VerticalAxis.rememberStart(
            label = rememberTextComponent(style = labelStyle),
            valueFormatter = startFormatter,
            itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 5 }) },
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            label = rememberTextComponent(style = labelStyle),
            valueFormatter = bottomFormatter,
            guideline = null,
            itemPlacer = remember {
                HorizontalAxis.ItemPlacer.aligned(
                    spacing = { max(1, (it.getOrNull(DetailViewModel.TimesKey)?.size ?: 0) / 4) },
                )
            },
        ),
        marker = rememberDefaultCartesianMarker(
            label = rememberTextComponent(
                style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            ),
            valueFormatter = markerFormatter,
        ),
    )

    CartesianChartHost(
        chart = chart,
        modelProducer = producer,
        modifier = Modifier.fillMaxSize(),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
    )
}

private fun axisPattern(range: ChartRange) = when (range) {
    ChartRange.D1 -> "HH:mm"
    ChartRange.W1 -> "EEE"
    ChartRange.M1 -> "d MMM"
    ChartRange.Y1 -> "MMM"
    ChartRange.Y5 -> "yyyy"
}

private fun markerPattern(range: ChartRange) = when (range) {
    ChartRange.D1, ChartRange.W1 -> "EEE HH:mm"
    ChartRange.M1 -> "d MMM HH:mm"
    ChartRange.Y1, ChartRange.Y5 -> "d MMM yyyy"
}
