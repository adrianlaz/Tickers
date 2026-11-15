package com.tickers.app.data

/** Abstraction over the market data provider so Yahoo can be swapped out later. */
interface MarketDataSource {
    suspend fun quote(symbol: String): Quote
    suspend fun chart(symbol: String, range: ChartRange): List<PricePoint>
    suspend fun search(query: String): List<SearchResult>
    /** Daily closes over a Yahoo range string ("1y", "2y", "5y", "10y"). */
    suspend fun dailyHistory(symbol: String, range: String): List<PricePoint>
}

data class Quote(
    val symbol: String,
    val name: String,
    val type: AssetType,
    val price: Double,
    /** Percent change: rolling 24h for crypto, vs. previous close for stocks/ETFs. */
    val changePct: Double,
    val currency: String,
    /** Epoch seconds of the last trade. */
    val marketTime: Long,
)

data class PricePoint(val epochSec: Long, val price: Double)

data class SearchResult(
    val symbol: String,
    val name: String,
    val type: AssetType,
    val exchange: String,
)

enum class AssetType {
    CRYPTO, STOCK, ETF, INDEX, OTHER;

    companion object {
        fun fromYahoo(raw: String?): AssetType = when (raw?.uppercase()) {
            "CRYPTOCURRENCY" -> CRYPTO
            "EQUITY" -> STOCK
            "ETF", "MUTUALFUND" -> ETF
            "INDEX" -> INDEX
            else -> OTHER
        }
    }
}

enum class ChartRange(val label: String, val yahooRange: String, val yahooInterval: String) {
    D1("1D", "1d", "5m"),
    W1("1W", "5d", "30m"),
    M1("1M", "1mo", "1h"),
    Y1("1Y", "1y", "1d"),
    Y5("5Y", "5y", "1wk"),
}
