package com.tickers.app.data

import com.tickers.app.data.remote.ChartResponse
import com.tickers.app.data.remote.SearchResponse
import com.tickers.app.data.remote.YahooDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses real Yahoo responses saved on 2026-11-15 (see test resources/fixtures). */
class YahooParsingTest {

    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText()

    private fun quote(name: String): Quote = YahooDataSource.toQuote(
        YahooDataSource.firstResult(
            YahooDataSource.json.decodeFromString<ChartResponse>(fixture(name))
        )
    )

    @Test
    fun btc_usesYahooRolling24hChange() {
        val q = quote("chart_BTC-USD_2d.json")
        assertEquals("BTC-USD", q.symbol)
        assertEquals(AssetType.CRYPTO, q.type)
        assertEquals(65346.67, q.price, 0.001)
        assertEquals(-2.966, q.changePct, 0.001)
        assertEquals("USD", q.currency)
    }

    @Test
    fun btc_fallbackComputesSameRolling24hFromSeries() {
        val result = YahooDataSource.firstResult(
            YahooDataSource.json.decodeFromString<ChartResponse>(fixture("chart_BTC-USD_2d.json"))
        )
        val series = YahooDataSource.toSeries(result)
        val pct = ChangeCalculator.changePct(
            type = AssetType.CRYPTO,
            price = result.meta.regularMarketPrice!!,
            marketTime = result.meta.regularMarketTime!!,
            yahooChangePct = null,
            previousClose = result.meta.previousClose,
            series = series,
        )
        // Yahoo reports -2.966; the nearest 15m bar is ~6 minutes off, so allow a small delta.
        assertEquals(-2.966, pct, 0.05)
    }

    @Test
    fun spy_isEtf_changeVsPreviousClose() {
        val q = quote("chart_SPY_2d.json")
        assertEquals(AssetType.ETF, q.type)
        assertEquals(754.37, q.price, 0.001)
        assertEquals(-0.363, q.changePct, 0.001)
        // Same as computing from previousClose (not chartPreviousClose, which is 2 sessions back).
        assertEquals(ChangeCalculator.pct(754.37, 757.12), q.changePct, 0.01)
    }

    @Test
    fun nvda_isStock() {
        val q = quote("chart_NVDA_1d.json")
        assertEquals(AssetType.STOCK, q.type)
        assertEquals("NVIDIA Corporation", q.name)
        assertEquals(-2.01, q.changePct, 0.001)
    }

    @Test
    fun series_skipsNullCloses() {
        val result = YahooDataSource.firstResult(
            YahooDataSource.json.decodeFromString<ChartResponse>(fixture("chart_ETH-USD_1d.json"))
        )
        val series = YahooDataSource.toSeries(result)
        assertTrue(series.isNotEmpty())
        assertTrue(series.zipWithNext().all { (a, b) -> a.epochSec < b.epochSec })
    }

    @Test
    fun search_parsesQuotes() {
        val r = YahooDataSource.json.decodeFromString<SearchResponse>(fixture("search_apple.json"))
        val aapl = r.quotes!!.first()
        assertEquals("AAPL", aapl.symbol)
        assertEquals("EQUITY", aapl.quoteType)
    }
}
