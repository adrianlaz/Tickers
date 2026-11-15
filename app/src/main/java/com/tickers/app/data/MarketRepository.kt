package com.tickers.app.data

import com.tickers.app.data.db.AssetDao
import com.tickers.app.data.db.AssetEntity
import com.tickers.app.data.db.QuoteEntity
import com.tickers.app.data.db.WatchItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MarketRepository(
    private val dao: AssetDao,
    private val source: MarketDataSource,
) {
    private val seedLock = Mutex()

    fun observeWatchlist(): Flow<List<WatchItem>> = dao.observeWatchlist()

    suspend fun watchlist(): List<WatchItem> = dao.watchlist()

    fun observeQuote(symbol: String): Flow<QuoteEntity?> = dao.observeQuote(symbol)

    suspend fun quote(symbol: String): QuoteEntity? = dao.quote(symbol)

    /** Inserts the default assets the first time the app runs. */
    suspend fun seedIfEmpty() = seedLock.withLock {
        if (dao.count() > 0) return@withLock
        dao.upsertAssets(DEFAULTS.mapIndexed { i, (symbol, name, type) ->
            AssetEntity(symbol, name, type, i)
        })
    }

    /**
     * Refreshes quotes for every asset, plus [extraSymbols] (e.g. Ticker widgets whose asset was
     * removed from the list), in parallel. Failures for single symbols keep the last cached
     * value. Returns true if at least one quote was updated.
     */
    suspend fun refreshAll(extraSymbols: Collection<String> = emptyList()): Boolean = coroutineScope {
        seedIfEmpty()
        (dao.assets().map { it.symbol } + extraSymbols).distinct()
            .map { symbol -> async { runCatching { refresh(symbol) }.isSuccess } }
            .awaitAll()
            .any { it }
    }

    suspend fun refresh(symbol: String) {
        val q = source.quote(symbol)
        dao.upsertQuote(QuoteEntity(symbol, q.price, q.changePct, q.currency, System.currentTimeMillis()))
    }

    suspend fun add(result: SearchResult) {
        dao.upsertAssets(listOf(AssetEntity(result.symbol, result.name, result.type, dao.maxSortOrder() + 1)))
        runCatching { refresh(result.symbol) }
    }

    suspend fun remove(symbol: String) = dao.deleteAsset(symbol)

    /** Persists a new order; [symbols] is the full list in display order. */
    suspend fun reorder(symbols: List<String>) {
        val bySymbol = dao.assets().associateBy { it.symbol }
        dao.upsertAssets(symbols.mapIndexedNotNull { i, s -> bySymbol[s]?.copy(sortOrder = i) })
    }

    suspend fun chart(symbol: String, range: ChartRange): List<PricePoint> = source.chart(symbol, range)

    suspend fun search(query: String): List<SearchResult> = source.search(query)

    suspend fun dailyHistory(symbol: String, range: String): List<PricePoint> = source.dailyHistory(symbol, range)

    companion object {
        val DEFAULTS = listOf(
            Triple("BTC-USD", "Bitcoin", AssetType.CRYPTO),
            Triple("ETH-USD", "Ethereum", AssetType.CRYPTO),
            Triple("SPY", "SPDR S&P 500 ETF", AssetType.ETF),
            Triple("NVDA", "NVIDIA", AssetType.STOCK),
        )
    }
}
