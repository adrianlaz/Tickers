package com.tickers.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
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

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.container.repository
        repo.seedIfEmpty()
        val initial = repo.watchlist()
        provideContent {
            val items by repo.observeWatchlist().collectAsState(initial)
            GlanceTheme { WatchlistContent(context, items) }
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
