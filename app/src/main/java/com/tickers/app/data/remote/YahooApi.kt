package com.tickers.app.data.remote

import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface YahooApi {
    @GET("v8/finance/chart/{symbol}")
    suspend fun chart(
        @Path("symbol") symbol: String,
        @Query("range") range: String,
        @Query("interval") interval: String,
    ): ChartResponse

    @GET("v1/finance/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("quotesCount") count: Int = 10,
        @Query("newsCount") newsCount: Int = 0,
    ): SearchResponse
}

@Serializable
data class ChartResponse(val chart: ChartEnvelope)

@Serializable
data class ChartEnvelope(val result: List<ChartResult>? = null, val error: YahooError? = null)

@Serializable
data class YahooError(val code: String? = null, val description: String? = null)

@Serializable
data class ChartResult(
    val meta: ChartMeta,
    val timestamp: List<Long>? = null,
    val indicators: Indicators? = null,
)

@Serializable
data class ChartMeta(
    val symbol: String,
    val currency: String? = null,
    val instrumentType: String? = null,
    val regularMarketPrice: Double? = null,
    val regularMarketTime: Long? = null,
    val regularMarketChangePercent: Double? = null,
    val previousClose: Double? = null,
    val chartPreviousClose: Double? = null,
    val longName: String? = null,
    val shortName: String? = null,
)

@Serializable
data class Indicators(val quote: List<QuoteIndicator>? = null)

@Serializable
data class QuoteIndicator(val close: List<Double?>? = null)

@Serializable
data class SearchResponse(val quotes: List<SearchQuote>? = null)

@Serializable
data class SearchQuote(
    val symbol: String,
    val shortname: String? = null,
    val longname: String? = null,
    val quoteType: String? = null,
    val exchDisp: String? = null,
)
