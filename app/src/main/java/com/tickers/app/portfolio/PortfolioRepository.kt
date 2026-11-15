package com.tickers.app.portfolio

import androidx.room.withTransaction
import com.tickers.app.data.MarketRepository
import com.tickers.app.data.PricePoint
import com.tickers.app.data.db.TickersDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Thrown when a change would leave the history inconsistent (e.g. selling more than owned). */
class PortfolioException(message: String) : Exception(message)

data class PortfolioData(
    val trades: List<Trade>,
    val allocations: List<Allocation>,
    val assets: List<AssetSummary>,
) {
    val value get() = assets.sumOf { it.value ?: 0.0 }
    val openCost get() = assets.sumOf { it.openCost }
    val unrealized get() = assets.sumOf { it.unrealized ?: 0.0 }
    val realized get() = assets.sumOf { it.realized }
    val totalPnl get() = unrealized + realized
    val totalBought get() = assets.sumOf { it.totalBought }
    val roiPct get() = if (totalBought > 0) totalPnl / totalBought * 100 else 0.0
}

class PortfolioRepository(
    private val db: TickersDatabase,
    private val market: MarketRepository,
) {
    private val dao = db.tradeDao()
    private val historyLock = Mutex()
    private val historyCache = mutableMapOf<String, Pair<Long, List<PricePoint>>>()

    fun observeTrades(): Flow<List<Trade>> = dao.observeTrades().map { list -> list.map { it.toTrade() } }

    /** Trades + allocations + live prices (from the quotes cache, not the watchlist). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observePortfolio(): Flow<PortfolioData> =
        combine(dao.observeTrades(), dao.observeAllocations()) { t, a ->
            t.map { it.toTrade() } to a.map { it.toAllocation() }
        }.flatMapLatest { (trades, allocations) ->
            val symbols = trades.map { it.symbol }.distinct()
            val prices: Flow<Map<String, Double>> = if (symbols.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(symbols.map { s -> market.observeQuote(s).map { s to it?.price } }) { pairs ->
                    pairs.mapNotNull { (s, p) -> p?.let { s to it } }.toMap()
                }
            }
            prices.map { PortfolioData(trades, allocations, PortfolioCalculator.summarize(trades, allocations, it)) }
        }

    suspend fun trade(id: Long): Trade? = dao.trade(id)?.toTrade()

    suspend fun symbols(): List<String> = dao.symbols()

    /** Fetch current prices for every traded asset (they may not be in the watchlist). */
    suspend fun refreshPrices() {
        for (s in dao.symbols()) runCatching { market.refresh(s) }
    }

    /**
     * Saves [trade] (insert if id == 0). For a manual sell, [manualLots] are its chosen lots.
     * All allocations are rebuilt in the same transaction; if the history becomes invalid the
     * whole change is rolled back and a [PortfolioException] explains why.
     */
    suspend fun save(trade: Trade, manualLots: List<Allocation> = emptyList()): Long = db.withTransaction {
        val id = dao.upsert(trade.toEntity()).let { if (trade.id != 0L) trade.id else it }
        val manual = dao.allocations().map { it.toAllocation() }
            .filter { it.sellId != id } + manualLots.map { it.copy(sellId = id) }.takeIf { !trade.autoAllocate }.orEmpty()
        rebuild(manual)
        id
    }

    suspend fun delete(id: Long) = db.withTransaction {
        dao.delete(id)
        rebuild(dao.allocations().map { it.toAllocation() })
    }

    /** Lots available to sell [sellId] at [executedAt], excluding that sell's own allocations. */
    suspend fun lotsAvailableFor(symbol: String, executedAt: Long, sellId: Long): List<OpenLot> {
        val trades = dao.trades().map { it.toTrade() }.filter { it.symbol == symbol && it.id != sellId }
        val probe = Trade(Long.MAX_VALUE, symbol, TradeSide.SELL, 0.0, 0.0, 0.0, executedAt)
        val before = trades.filter { PortfolioCalculator.chronological.compare(it, probe) < 0 }
        val manual = dao.allocations().map { it.toAllocation() }
        val alloc = PortfolioCalculator.allocate(before, manual.filter { a -> before.any { it.id == a.sellId } })
        return PortfolioCalculator.summarize(before, alloc.allocations, emptyMap())
            .firstOrNull()?.openLots.orEmpty()
    }

    /** Current allocations of one sell (to pre-fill the lot picker when editing). */
    suspend fun allocationsOf(sellId: Long): List<Allocation> =
        dao.allocations().map { it.toAllocation() }.filter { it.sellId == sellId }

    private suspend fun rebuild(previous: List<Allocation>) {
        val trades = dao.trades().map { it.toTrade() }
        val manualSells = trades.filter { it.side == TradeSide.SELL && !it.autoAllocate }.map { it.id }.toSet()
        val result = PortfolioCalculator.allocate(trades, previous.filter { it.sellId in manualSells })
        if (result.errors.isNotEmpty()) throw PortfolioException(result.errors.first())
        dao.deleteAllAllocations()
        dao.insertAllocations(result.allocations.map { it.toEntity() })
    }

    /** Daily closes since the first trade, cached for an hour. */
    suspend fun dailyHistory(symbol: String, sinceMillis: Long): List<PricePoint> = historyLock.withLock {
        val ageDays = (System.currentTimeMillis() - sinceMillis) / 86_400_000L
        val range = when {
            ageDays < 360 -> "1y"
            ageDays < 720 -> "2y"
            ageDays < 1800 -> "5y"
            else -> "10y"
        }
        val key = "$symbol/$range"
        historyCache[key]?.takeIf { System.currentTimeMillis() - it.first < 3_600_000 }?.let { return@withLock it.second }
        market.dailyHistory(symbol, range).also { historyCache[key] = System.currentTimeMillis() to it }
    }

    // --- Backup ---

    suspend fun exportJson(): String = Backup.encode(
        dao.trades().map { it.toTrade() to it.quoteCurrency },
        dao.allocations().map { it.toAllocation() },
    )

    /** Replaces all trades with the backup's. Returns the number of trades imported. */
    suspend fun importJson(json: String): Int {
        val data = Backup.decode(json)
        return db.withTransaction {
            dao.deleteAllAllocations()
            dao.deleteAllTrades()
            dao.insertTrades(data.trades.map { (t, q) -> t.toEntity(q) })
            rebuild(data.allocations)
            data.trades.size
        }
    }
}
