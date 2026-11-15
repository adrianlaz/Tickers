package com.tickers.app.data.remote

import com.tickers.app.data.AssetType
import com.tickers.app.data.ChangeCalculator
import com.tickers.app.data.ChartRange
import com.tickers.app.data.MarketDataSource
import com.tickers.app.data.PricePoint
import com.tickers.app.data.Quote
import com.tickers.app.data.SearchResult
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

class YahooDataSource(private val api: YahooApi) : MarketDataSource {

    override suspend fun quote(symbol: String): Quote =
        // 2d/15m keeps enough history for the crypto rolling-24h fallback.
        toQuote(firstResult(api.chart(symbol, "2d", "15m")))

    override suspend fun chart(symbol: String, range: ChartRange): List<PricePoint> =
        toSeries(firstResult(api.chart(symbol, range.yahooRange, range.yahooInterval)))

    override suspend fun search(query: String): List<SearchResult> =
        api.search(query).quotes.orEmpty()
            .filter { it.quoteType != null && it.quoteType !in IGNORED_TYPES }
            .map {
                SearchResult(
                    symbol = it.symbol,
                    name = it.longname ?: it.shortname ?: it.symbol,
                    type = AssetType.fromYahoo(it.quoteType),
                    exchange = it.exchDisp.orEmpty(),
                )
            }

    companion object {
        private const val BASE_URL = "https://query1.finance.yahoo.com/"

        // A full Chrome UA string gets HTTP 429 without cookies; a bare one is accepted.
        private const val USER_AGENT = "Mozilla/5.0"

        private val IGNORED_TYPES = setOf("FUTURE", "OPTION")

        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun create(): YahooDataSource {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder().header("User-Agent", USER_AGENT).build()
                    )
                }
                .build()
            val api = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(YahooApi::class.java)
            return YahooDataSource(api)
        }

        fun firstResult(response: ChartResponse): ChartResult =
            response.chart.result?.firstOrNull()
                ?: throw IOException(response.chart.error?.description ?: "No data")

        fun toSeries(result: ChartResult): List<PricePoint> {
            val ts = result.timestamp.orEmpty()
            val closes = result.indicators?.quote?.firstOrNull()?.close.orEmpty()
            return ts.indices.mapNotNull { i ->
                closes.getOrNull(i)?.let { PricePoint(ts[i], it) }
            }
        }

        fun toQuote(result: ChartResult): Quote {
            val meta = result.meta
            val series = toSeries(result)
            val type = AssetType.fromYahoo(meta.instrumentType)
            val price = meta.regularMarketPrice ?: series.lastOrNull()?.price
                ?: throw IOException("No price for ${meta.symbol}")
            val time = meta.regularMarketTime ?: series.lastOrNull()?.epochSec ?: 0L
            return Quote(
                symbol = meta.symbol,
                name = meta.longName ?: meta.shortName ?: meta.symbol,
                type = type,
                price = price,
                changePct = ChangeCalculator.changePct(
                    type = type,
                    price = price,
                    marketTime = time,
                    yahooChangePct = meta.regularMarketChangePercent,
                    previousClose = meta.previousClose,
                    series = series,
                ),
                currency = meta.currency ?: "USD",
                marketTime = time,
            )
        }
    }
}
