package com.tickers.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.tickers.app.container
import com.tickers.app.ui.detail.DetailScreen
import com.tickers.app.ui.portfolio.PortfolioScreen
import com.tickers.app.ui.search.SearchScreen
import com.tickers.app.ui.settings.SettingsScreen
import com.tickers.app.ui.theme.TickersTheme
import com.tickers.app.ui.trades.EditTradeScreen
import com.tickers.app.ui.trades.TradesScreen
import com.tickers.app.ui.watchlist.WatchlistScreen
import com.tickers.app.update.UpdateState
import com.tickers.app.update.Updater
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

// Bottom-bar tabs.
@Serializable object WatchlistRoute
@Serializable object PortfolioRoute
@Serializable object TradesRoute
@Serializable object SettingsRoute

// Full-screen destinations (no bottom bar).
@Serializable object SearchRoute
@Serializable data class DetailRoute(val symbol: String)
@Serializable data class EditTradeRoute(val id: Long = 0)

private data class Tab(val route: Any, val type: KClass<*>, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(WatchlistRoute, WatchlistRoute::class, "Markets", Icons.AutoMirrored.Filled.ShowChart),
    Tab(PortfolioRoute, PortfolioRoute::class, "Portfolio", Icons.Default.AccountBalanceWallet),
    Tab(TradesRoute, TradesRoute::class, "Trades", Icons.Default.SwapHoriz),
    Tab(SettingsRoute, SettingsRoute::class, "Settings", Icons.Default.Settings),
)

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
                val updater = container.updater

                // Automatic update check (throttled), whichever tab is open.
                LaunchedEffect(Unit) { updater.checkIfDue() }

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

                val entry by nav.currentBackStackEntryAsState()
                val destination = entry?.destination
                val onTab = tabs.any { tab -> destination?.hierarchy?.any { it.hasRoute(tab.type) } == true }
                val update by updater.state.collectAsStateWithLifecycle()

                Scaffold(
                    // Inner screens draw their own top bars and status-bar insets.
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = {
                        if (onTab) BottomBar(nav, destination, updateAvailable = update is UpdateState.Available)
                    },
                ) { padding ->
                    val bottom = PaddingValues(bottom = padding.calculateBottomPadding())
                    NavHost(
                        nav,
                        startDestination = WatchlistRoute,
                        modifier = Modifier.padding(bottom).consumeWindowInsets(bottom),
                    ) {
                        composable<WatchlistRoute> {
                            WatchlistScreen(
                                onOpen = { nav.navigate(DetailRoute(it)) },
                                onAdd = { nav.navigate(SearchRoute) },
                            )
                        }
                        composable<PortfolioRoute> {
                            PortfolioScreen(onAddTrade = { nav.navigate(EditTradeRoute()) })
                        }
                        composable<TradesRoute> {
                            TradesScreen(
                                onAdd = { nav.navigate(EditTradeRoute()) },
                                onOpen = { nav.navigate(EditTradeRoute(it)) },
                            )
                        }
                        composable<SettingsRoute> { SettingsScreen() }
                        composable<SearchRoute> { SearchScreen(onDone = { nav.popBackStack() }) }
                        composable<DetailRoute> { e ->
                            DetailScreen(symbol = e.toRoute<DetailRoute>().symbol, onBack = { nav.popBackStack() })
                        }
                        composable<EditTradeRoute> { e ->
                            EditTradeScreen(tradeId = e.toRoute<EditTradeRoute>().id, onDone = { nav.popBackStack() })
                        }
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

@Composable
private fun BottomBar(nav: NavHostController, destination: androidx.navigation.NavDestination?, updateAvailable: Boolean) {
    NavigationBar {
        tabs.forEach { tab ->
            val selected = destination?.hierarchy?.any { it.hasRoute(tab.type) } == true
            NavigationBarItem(
                selected = selected,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = {
                    if (tab.route == SettingsRoute && updateAvailable) {
                        BadgedBox(badge = { Badge() }) { Icon(tab.icon, contentDescription = null) }
                    } else {
                        Icon(tab.icon, contentDescription = null)
                    }
                },
                label = { Text(tab.label) },
            )
        }
    }
}
