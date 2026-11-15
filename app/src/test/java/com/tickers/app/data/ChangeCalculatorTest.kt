package com.tickers.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChangeCalculatorTest {

    private val now = 1_800_000_000L
    private val day = 86_400L

    private fun series(vararg points: Pair<Long, Double>) = points.map { PricePoint(it.first, it.second) }

    @Test
    fun prefersYahooValue() {
        val pct = ChangeCalculator.changePct(AssetType.CRYPTO, 110.0, now, 1.23, 50.0, emptyList())
        assertEquals(1.23, pct, 1e-9)
    }

    @Test
    fun crypto_fallback_usesBarNearest24hAgo() {
        val s = series(now - day - 600 to 100.0, now - day + 3000 to 90.0, now to 110.0)
        val pct = ChangeCalculator.changePct(AssetType.CRYPTO, 110.0, now, null, 50.0, s)
        assertEquals(10.0, pct, 1e-9)
    }

    @Test
    fun crypto_fallback_gapTooLarge_usesPreviousClose() {
        val s = series(now - day - 7200 to 100.0, now to 110.0)
        val pct = ChangeCalculator.changePct(AssetType.CRYPTO, 120.0, now, null, 80.0, s)
        assertEquals(50.0, pct, 1e-9)
    }

    @Test
    fun stock_fallback_usesPreviousClose_notSeries() {
        val s = series(now - day to 50.0)
        val pct = ChangeCalculator.changePct(AssetType.STOCK, 105.0, now, null, 100.0, s)
        assertEquals(5.0, pct, 1e-9)
    }

    @Test
    fun missingBase_isZero() {
        assertEquals(0.0, ChangeCalculator.changePct(AssetType.STOCK, 10.0, now, null, null, emptyList()), 0.0)
        assertEquals(0.0, ChangeCalculator.pct(10.0, 0.0), 0.0)
    }

    @Test
    fun priceAt_emptySeries_isNull() {
        assertNull(ChangeCalculator.priceAt(emptyList(), now))
    }
}
