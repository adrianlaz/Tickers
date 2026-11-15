package com.tickers.app.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import com.tickers.app.container
import com.tickers.app.ui.Format
import com.tickers.app.ui.theme.TickersTheme
import kotlinx.coroutines.launch

/** Shown by the launcher when a single-asset widget is placed (or reconfigured). */
class SingleAssetConfigActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        // If the user backs out, the launcher must discard the widget.
        setResult(RESULT_CANCELED, resultIntent(widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val repo = container.repository
        lifecycleScope.launch { repo.seedIfEmpty() }

        setContent {
            TickersTheme {
                val items by repo.observeWatchlist().collectAsState(emptyList())
                Scaffold(topBar = { TopAppBar(title = { Text("Choose asset") }) }) { padding ->
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                        items(items, key = { it.symbol }) { item ->
                            ListItem(
                                headlineContent = { Text(Format.ticker(item.symbol)) },
                                supportingContent = { Text(item.name) },
                                trailingContent = { Text(Format.price(item.price, item.currency)) },
                                modifier = Modifier.clickable { choose(widgetId, item.symbol) },
                            )
                        }
                    }
                }
            }
        }
    }

    private fun choose(widgetId: Int, symbol: String) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@SingleAssetConfigActivity).getGlanceIdBy(widgetId)
            updateAppWidgetState(this@SingleAssetConfigActivity, glanceId) {
                it[SingleAssetWidget.SYMBOL_KEY] = symbol
            }
            SingleAssetWidget().update(this@SingleAssetConfigActivity, glanceId)
            setResult(RESULT_OK, resultIntent(widgetId))
            finish()
        }
    }

    private fun resultIntent(widgetId: Int) =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
}
