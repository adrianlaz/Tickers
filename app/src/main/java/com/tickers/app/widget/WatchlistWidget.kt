package com.tickers.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tickers.app.R
import com.tickers.app.container
import com.tickers.app.data.db.WatchItem
import com.tickers.app.ui.Format
import com.tickers.app.work.RefreshWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WatchlistWidget : GlanceAppWidget() {

    /** Recompose with the real size: what fits is decided from it, never left to clipping. */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.container.repository
        repo.seedIfEmpty()
        val initial = repo.watchlist()
        provideContent {
            val items by repo.observeWatchlist().collectAsState(initial)
            GlanceTheme {
                val size = LocalSize.current
                if (size.height < COMPACT_MAX_HEIGHT) {
                    CompactContent(context, items, size.width, size.height)
                } else {
                    ListContent(context, items, size.height)
                }
            }
        }
    }
}

class WatchlistWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WatchlistWidget()

    /** First widget of this kind placed: fetch now instead of waiting for the periodic job. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.refreshNow(context)
    }
}

/** Below this height a vertical list shows too few rows, so assets go side by side. */
private val COMPACT_MAX_HEIGHT = 110.dp

/** Launchers may report a slightly larger size than they draw; keep this much in reserve. */
private val SAFETY = 6.dp

// List layout sizes.
private const val ROW_TEXT_SP = 15
private const val CHANGE_TEXT_SP = 13
private const val HEADER_TEXT_SP = 11
private val ROW_MIN = lineHeight(ROW_TEXT_SP) + 6.dp // ~28dp
private val ROW_MAX = 44.dp
private val HEADER_HEIGHT = 26.dp

// Compact (side by side) sizes.
private const val CELL_TICKER_SP = 13
private const val CELL_PRICE_SP = 13
private const val CELL_CHANGE_SP = 11

/** Narrowest cell that still fits "$65,347" and "▼ 2.97%". */
private val MIN_CELL_WIDTH = 68.dp

/**
 * Vertical list. Shows only as many rows as fit completely; the header ("Updated HH:mm" +
 * refresh) is shown only when all assets still fit below it.
 */
@Composable
private fun ListContent(context: Context, items: List<WatchItem>, height: Dp) {
    val available = height - WidgetPadding.vertical * 2 - SAFETY
    val fitWithHeader = rowsThatFit(available - HEADER_HEIGHT)
    val showHeader = items.isEmpty() || fitWithHeader >= items.size
    val rowsSpace = if (showHeader) available - HEADER_HEIGHT else available
    val shown = items.take(rowsThatFit(rowsSpace))
    val rowHeight = if (shown.isEmpty()) ROW_MIN else min(rowsSpace / shown.size, ROW_MAX)

    Column(
        modifier = widgetSurface().padding(horizontal = WidgetPadding.horizontal, vertical = WidgetPadding.vertical),
    ) {
        if (showHeader) Header(items)
        shown.forEach { item -> ListRow(context, item, rowHeight) }
    }
}

/** Glance renders at most 10 children per Row/Column; header + 9 rows stays within that. */
private const val MAX_CHILDREN = 10

private fun rowsThatFit(space: Dp): Int =
    (space.value / ROW_MIN.value).toInt().coerceIn(0, MAX_CHILDREN - 1)

@Composable
private fun Header(items: List<WatchItem>) {
    val updated = items.mapNotNull { it.updatedAt }.maxOrNull()
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(HEADER_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = updated?.let { "Updated " + time(it) } ?: "Not updated yet",
            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = HEADER_TEXT_SP.sp),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = "Refresh",
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
            modifier = GlanceModifier.size(24.dp).padding(3.dp)
                .clickable(actionRunCallback<RefreshAction>()),
        )
    }
}

@Composable
private fun ListRow(context: Context, item: WatchItem, rowHeight: Dp) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(rowHeight)
            .clickable(actionStartActivity(Widgets.detailIntent(context, item.symbol))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Format.ticker(item.symbol),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = ROW_TEXT_SP.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = Format.price(item.price, item.currency),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = ROW_TEXT_SP.sp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(10.dp))
        Text(
            text = Format.change(item.changePct),
            style = TextStyle(
                color = Widgets.changeColor(item.changePct),
                fontSize = CHANGE_TEXT_SP.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
        )
    }
}

/**
 * Assets side by side (short widgets such as 4x1), as many as fit the width, in list order.
 * Three lines per cell when the height allows, otherwise ticker + change share the first line.
 */
@Composable
private fun CompactContent(context: Context, items: List<WatchItem>, width: Dp, height: Dp) {
    val inner = height - WidgetPadding.compactVertical * 2 - SAFETY
    val threeLines = lineHeight(CELL_TICKER_SP) + lineHeight(CELL_PRICE_SP) + lineHeight(CELL_CHANGE_SP)
    val usable = width - WidgetPadding.horizontal * 2
    val fit = (usable.value / MIN_CELL_WIDTH.value).toInt().coerceIn(1, MAX_CHILDREN)
    Row(
        modifier = widgetSurface().padding(horizontal = WidgetPadding.horizontal, vertical = WidgetPadding.compactVertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.take(fit).forEach { item ->
            CompactCell(context, item, twoLines = inner < threeLines, modifier = GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun CompactCell(context: Context, item: WatchItem, twoLines: Boolean, modifier: GlanceModifier) {
    val tickerStyle = TextStyle(
        color = GlanceTheme.colors.onSurface,
        fontSize = CELL_TICKER_SP.sp,
        fontWeight = FontWeight.Bold,
    )
    val changeStyle = TextStyle(
        color = Widgets.changeColor(item.changePct),
        fontSize = CELL_CHANGE_SP.sp,
        fontWeight = FontWeight.Medium,
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(actionStartActivity(Widgets.detailIntent(context, item.symbol))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (twoLines) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Format.ticker(item.symbol), style = tickerStyle, maxLines = 1)
                Spacer(GlanceModifier.width(4.dp))
                Text(Format.change(item.changePct), style = changeStyle, maxLines = 1)
            }
        } else {
            Text(Format.ticker(item.symbol), style = tickerStyle, maxLines = 1)
        }
        Text(
            text = Format.priceCompact(item.price, item.currency),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = CELL_PRICE_SP.sp),
            maxLines = 1,
        )
        if (!twoLines) {
            Text(Format.change(item.changePct), style = changeStyle, maxLines = 1)
        }
    }
}

private fun time(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
