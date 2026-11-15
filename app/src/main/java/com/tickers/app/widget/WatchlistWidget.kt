package com.tickers.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
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

    /** Recompose with the real size so short widgets (e.g. 4x1) get the compact layout. */
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
                    CompactContent(context, items, size.width, veryShort = size.height < TWO_LINE_MAX_HEIGHT)
                } else {
                    WatchlistContent(context, items)
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

/** Below this height the vertical list can't show more than ~2 rows, so go horizontal. */
private val COMPACT_MAX_HEIGHT = 110.dp

/** Below this height three lines don't fit: put ticker and change on one line. */
private val TWO_LINE_MAX_HEIGHT = 64.dp

/** Narrowest cell that still fits "$65,347" and "▼ 2.97%". */
private val MIN_CELL_WIDTH = 68.dp

/** Assets side by side, as many as fit the width (in list order). */
@Composable
private fun CompactContent(context: Context, items: List<WatchItem>, width: Dp, veryShort: Boolean) {
    val fit = (width.value / MIN_CELL_WIDTH.value).toInt().coerceAtLeast(1)
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.take(fit).forEach { item ->
            CompactCell(context, item, veryShort, GlanceModifier.defaultWeight())
        }
    }
}

@Composable
private fun CompactCell(context: Context, item: WatchItem, veryShort: Boolean, modifier: GlanceModifier) {
    val tickerStyle = TextStyle(
        color = GlanceTheme.colors.onSurface,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
    val changeStyle = TextStyle(
        color = Widgets.changeColor(item.changePct),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
    )
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clickable(actionStartActivity(Widgets.detailIntent(context, item.symbol))),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (veryShort) {
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
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp),
            maxLines = 1,
        )
        if (!veryShort) {
            Text(Format.change(item.changePct), style = changeStyle, maxLines = 1)
        }
    }
}

@Composable
private fun WatchlistContent(context: Context, items: List<WatchItem>) {
    val updated = items.mapNotNull { it.updatedAt }.maxOrNull()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = updated?.let { "Updated " + time(it) } ?: "Not updated yet",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                modifier = GlanceModifier.defaultWeight(),
            )
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "Refresh",
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.size(28.dp).padding(4.dp)
                    .clickable(actionRunCallback<RefreshAction>()),
            )
        }
        LazyColumn {
            items(items, itemId = { it.symbol.hashCode().toLong() }) { item ->
                WatchRow(context, item)
            }
        }
    }
}

@Composable
private fun WatchRow(context: Context, item: WatchItem) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(actionStartActivity(Widgets.detailIntent(context, item.symbol))),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Format.ticker(item.symbol),
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            ),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            text = Format.price(item.price, item.currency),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp),
        )
        Spacer(GlanceModifier.width(10.dp))
        Text(
            text = Format.change(item.changePct),
            style = TextStyle(
                color = Widgets.changeColor(item.changePct),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

private fun time(epochMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(epochMillis))
