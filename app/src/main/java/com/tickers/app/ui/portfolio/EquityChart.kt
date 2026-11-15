package com.tickers.app.ui.portfolio

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.tickers.app.portfolio.EquityPoint
import com.tickers.app.ui.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val TimesKey = ExtraStore.Key<List<Long>>()

private object Padded : CartesianLayerRangeProvider {
    private fun pad(minY: Double, maxY: Double) = max((maxY - minY) * 0.08, abs(maxY) * 0.001)
    override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = minY - pad(minY, maxY)
    override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = maxY + pad(minY, maxY)
}

/** Daily position value (colored) vs. cost basis (grey). */
@Composable
fun EquityChart(points: List<EquityPoint>, valueColor: Color, costColor: Color) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(points) {
        if (points.size >= 2) {
            producer.runTransaction {
                lineModel {
                    series(points.map { it.value })
                    series(points.map { it.costBasis })
                }
                extras { it[TimesKey] = points.map { p -> p.epochSec } }
            }
        }
    }
    val dateFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    fun label(store: ExtraStore, x: Double): String =
        store.getOrNull(TimesKey)?.getOrNull(x.toInt())?.let { dateFormat.format(Date(it * 1000)) } ?: ""

    val labelStyle = TextStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(
                LineCartesianLayer.rememberLine(fill = remember(valueColor) { LineCartesianLayer.LineFill.single(Fill(valueColor)) }),
                LineCartesianLayer.rememberLine(fill = remember(costColor) { LineCartesianLayer.LineFill.single(Fill(costColor)) }),
            ),
            rangeProvider = Padded,
        ),
        startAxis = VerticalAxis.rememberStart(
            label = rememberTextComponent(style = labelStyle),
            valueFormatter = remember { CartesianValueFormatter { _, y, _ -> Format.usd(y).removeSuffix(".00") } },
            itemPlacer = remember { VerticalAxis.ItemPlacer.count(count = { 4 }) },
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            label = rememberTextComponent(style = labelStyle),
            valueFormatter = remember { CartesianValueFormatter { ctx, x, _ -> label(ctx.model.extraStore, x) } },
            guideline = null,
            itemPlacer = remember {
                HorizontalAxis.ItemPlacer.aligned(spacing = { max(1, (it.getOrNull(TimesKey)?.size ?: 0) / 3) })
            },
        ),
        marker = rememberDefaultCartesianMarker(
            label = rememberTextComponent(style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)),
            valueFormatter = remember {
                DefaultCartesianMarker.ValueFormatter { ctx, targets ->
                    val t = targets.firstOrNull() as? LineCartesianLayerMarkerTarget ?: return@ValueFormatter ""
                    val ys = t.points.map { it.entry.y }
                    val value = ys.getOrNull(0)
                    val cost = ys.getOrNull(1)
                    listOfNotNull(
                        label(ctx.model.extraStore, t.x),
                        "value ${Format.usd(value)}",
                        cost?.let { "cost ${Format.usd(it)}" },
                    ).joinToString("  ·  ")
                }
            },
        ),
    )
    CartesianChartHost(
        chart = chart,
        modelProducer = producer,
        modifier = Modifier.fillMaxWidth().height(220.dp),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
    )
}
