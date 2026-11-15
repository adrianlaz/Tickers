package com.tickers.app.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.fillMaxSize
import com.tickers.app.R
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.color.ColorProvider
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.tickers.app.ui.MainActivity
import com.tickers.app.work.RefreshWorker

object Widgets {
    suspend fun updateAll(context: Context) {
        WatchlistWidget().updateAll(context)
        SingleAssetWidget().updateAll(context)
    }

    /** Symbols that placed Ticker widgets are configured to show. */
    suspend fun tileSymbols(context: Context): Set<String> {
        val manager = GlanceAppWidgetManager(context)
        return manager.getGlanceIds(SingleAssetWidget::class.java).mapNotNull { id ->
            getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[SingleAssetWidget.SYMBOL_KEY]
        }.toSet()
    }

    /** Opens the app on the chart for [symbol]. Unique data URI keeps PendingIntents distinct. */
    fun detailIntent(context: Context, symbol: String): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("tickers://asset/${Uri.encode(symbol)}"))
            .putExtra(MainActivity.EXTRA_SYMBOL, symbol)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    val up = ColorProvider(day = Color(0xFF1B873F), night = Color(0xFF4ADE80))
    val down = ColorProvider(day = Color(0xFFC62828), night = Color(0xFFF87171))

    fun changeColor(pct: Double?) = if ((pct ?: 0.0) >= 0) up else down
}

/**
 * Root surface for every widget: marked as the widget background (so Android 12+ launchers clip
 * it with their own corner radius) and rounded with that same radius. Content inside must keep
 * [WidgetPadding] away from the edges, or the rounded corners cut into it.
 */
@Composable
fun widgetSurface(): GlanceModifier = GlanceModifier
    .fillMaxSize()
    .appWidgetBackground()
    .background(GlanceTheme.colors.widgetBackground)
    .cornerRadius(R.dimen.widget_radius)

/**
 * Insets that keep text out of the rounded corners. Launcher radii go up to ~28dp; a corner of
 * radius r cuts about 0.3 r into the diagonal, so ~10dp keeps glyphs clear.
 */
object WidgetPadding {
    val horizontal = 14.dp
    val vertical = 10.dp
    val compactVertical = 6.dp
}

/**
 * Text line heights in dp per font size, with Android's font padding and ~10% headroom for a
 * slightly larger system font scale. Used to decide what fits instead of letting text clip.
 */
fun lineHeight(sp: Int): Dp = (sp * 1.45f).dp

/** Widget refresh button: fetch now instead of waiting for the 15-minute job. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshWorker.refreshNow(context)
    }
}
