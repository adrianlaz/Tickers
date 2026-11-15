package com.tickers.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.padding
import androidx.glance.layout.width
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

    /** Recompose with the real size so the tile picks a layout that fits (2x1, 1x1, 2x2...). */
    override val sizeMode: SizeMode = SizeMode.Exact

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
            GlanceTheme { Tile(context, symbol, quote, LocalSize.current) }
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

/** How much text the tile shows, from roomiest to tightest. */
private enum class TileLayout(
    val tickerSp: Int,
    val priceSp: Int,
    val changeSp: Int,
    val verticalPadding: Dp,
) {
    /** Ticker / price / change on three lines. */
    LARGE(13, 18, 13, WidgetPadding.vertical),
    SMALL(11, 15, 11, WidgetPadding.compactVertical),

    /** "ETH ▼ 4.47%" on the first line, price below. */
    TWO_LINES(11, 14, 11, 4.dp),

    /** Price and change on a single line. */
    ONE_LINE(0, 13, 11, 2.dp);

    /** Height this layout needs, text plus padding. */
    val needed: Dp
        get() = verticalPadding * 2 + when (this) {
            LARGE, SMALL -> lineHeight(tickerSp) + lineHeight(priceSp) + lineHeight(changeSp)
            TWO_LINES -> lineHeight(tickerSp) + lineHeight(priceSp)
            ONE_LINE -> lineHeight(priceSp)
        }

    companion object {
        /** Launchers may report slightly more height than they draw. */
        private val SAFETY = 6.dp

        fun pick(height: Dp): TileLayout =
            entries.firstOrNull { it.needed + SAFETY <= height } ?: ONE_LINE
    }
}

@Composable
private fun Tile(context: Context, symbol: String?, item: QuoteEntity?, size: DpSize) {
    val layout = TileLayout.pick(size.height)
    var modifier = widgetSurface()
        .padding(horizontal = WidgetPadding.horizontal, vertical = layout.verticalPadding)
    if (symbol != null) {
        modifier = modifier.clickable(actionStartActivity(Widgets.detailIntent(context, symbol)))
    }
    Column(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (symbol == null) {
            Text(
                text = "Hold to set up",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                maxLines = 2,
            )
            return@Column
        }
        val tickerStyle = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = layout.tickerSp.sp,
            fontWeight = FontWeight.Bold,
        )
        val priceStyle = TextStyle(
            color = GlanceTheme.colors.onSurface,
            fontSize = layout.priceSp.sp,
            fontWeight = FontWeight.Bold,
        )
        val changeStyle = TextStyle(
            color = Widgets.changeColor(item?.changePct),
            fontSize = layout.changeSp.sp,
            fontWeight = FontWeight.Medium,
        )
        // Below ~110dp wide, drop the cents on big prices so the line isn't cut.
        val price = if (size.width < 110.dp) {
            Format.priceCompact(item?.price, item?.currency)
        } else {
            Format.price(item?.price, item?.currency)
        }
        val change = Format.change(item?.changePct)

        when (layout) {
            TileLayout.LARGE, TileLayout.SMALL -> {
                Text(Format.ticker(symbol), style = tickerStyle, maxLines = 1)
                Text(price, style = priceStyle, maxLines = 1)
                Text(change, style = changeStyle, maxLines = 1)
            }
            TileLayout.TWO_LINES -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.ticker(symbol), style = tickerStyle, maxLines = 1)
                    Spacer(GlanceModifier.width(6.dp))
                    Text(change, style = changeStyle, maxLines = 1)
                }
                Text(price, style = priceStyle, maxLines = 1)
            }
            TileLayout.ONE_LINE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(price, style = priceStyle, maxLines = 1)
                Spacer(GlanceModifier.width(6.dp))
                Text(change, style = changeStyle, maxLines = 1)
            }
        }
    }
}
