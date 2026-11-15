package com.tickers.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.tickers.app.container
import com.tickers.app.ui.detail.DetailScreen
import com.tickers.app.ui.search.SearchScreen
import com.tickers.app.ui.theme.TickersTheme
import com.tickers.app.ui.watchlist.WatchlistScreen
import com.tickers.app.update.Updater
import kotlinx.serialization.Serializable

@Serializable object WatchlistRoute
@Serializable object SearchRoute
@Serializable data class DetailRoute(val symbol: String)

class MainActivity : ComponentActivity() {

    /** Symbol requested by a widget tap; consumed once navigation has happened. */
    private val pendingSymbol = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            TickersTheme {
                val nav = rememberNavController()
                val symbol = pendingSymbol.value
                LaunchedEffect(symbol) {
                    if (symbol != null) {
                        nav.navigate(DetailRoute(symbol)) {
                            popUpTo(WatchlistRoute)
                            launchSingleTop = true
                        }
                        pendingSymbol.value = null
                    }
                }
                NavHost(nav, startDestination = WatchlistRoute) {
                    composable<WatchlistRoute> {
                        WatchlistScreen(
                            onOpen = { nav.navigate(DetailRoute(it)) },
                            onAdd = { nav.navigate(SearchRoute) },
                        )
                    }
                    composable<SearchRoute> {
                        SearchScreen(onDone = { nav.popBackStack() })
                    }
                    composable<DetailRoute> { entry ->
                        DetailScreen(
                            symbol = entry.toRoute<DetailRoute>().symbol,
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Updater.ACTION_INSTALL_STATUS) {
            container.updater.onInstallStatus(this, intent)
            return
        }
        intent.getStringExtra(EXTRA_SYMBOL)?.let { pendingSymbol.value = it }
    }

    companion object {
        const val EXTRA_SYMBOL = "symbol"
    }
}
