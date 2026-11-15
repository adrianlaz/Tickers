package com.tickers.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.tickers.app.container
import com.tickers.app.data.db.QuoteEntity
import com.tickers.app.ui.Format
import com.tickers.app.work.RefreshWorker
import kotlinx.coroutines.flow.flowOf

/** One asset per widget; the symbol is chosen in [SingleAssetConfigActivity]. */
class SingleAssetWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = context.container.repository
        // Preload so the first frame already has data instead of flashing placeholders.
        val initialSymbol = getAppWidgetState<Preferences>(context, id)[SYMBOL_KEY]
        val initialQuote = initialSymbol?.let { repo.quote(it) }
        provideContent {
            val symbol = currentState<Preferences>()[SYMBOL_KEY]
            // Reads the quote directly (not via the watchlist), so a tile keeps working even
            // if its asset is removed from the list; RefreshWorker keeps refreshing it.
            val quote by remember(symbol) {
                symbol?.let(repo::observeQuote) ?: flowOf(null)
            }.collectAsState(if (symbol == initialSymbol) initialQuote else null)
            GlanceTheme { Tile(context, symbol, quote) }
        }
    }

    companion object {
        val SYMBOL_KEY = stringPreferencesKey("symbol")
    }
}

class SingleAssetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SingleAssetWidget()

    /** First widget of this kind placed: fetch now instead of waiting for the periodic job. */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.refreshNow(context)
    }
}

@Composable
private fun Tile(context: Context, symbol: String?, item: QuoteEntity?) {
    var modifier = GlanceModifier
        .fillMaxSize()
        .background(GlanceTheme.colors.widgetBackground)
        .cornerRadius(16.dp)
        .padding(10.dp)
    if (symbol != null) {
        modifier = modifier.clickable(actionStartActivity(Widgets.detailIntent(context, symbol)))
    }
    Column(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = symbol?.let(Format::ticker) ?: "Tap and hold to set up",
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
        if (symbol != null) {
            Text(
                text = Format.price(item?.price, item?.currency),
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            Text(
                text = Format.change(item?.changePct),
                style = TextStyle(
                    color = Widgets.changeColor(item?.changePct),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}
