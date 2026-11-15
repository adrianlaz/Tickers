package com.tickers.app.data

import kotlin.math.abs

/**
 * 24h change rule.
 *
 * Yahoo's `regularMarketChangePercent` is already a rolling 24h change for crypto and the change
 * vs. previous session close for stocks/ETFs (verified against live responses), so it is used
 * when present. The fallbacks only run if Yahoo omits that field.
 */
object ChangeCalculator {

    private const val DAY_SEC = 24 * 60 * 60L

    /** Max distance between the target time and the chosen bar (one hour). */
    private const val TOLERANCE_SEC = 60 * 60L

    fun changePct(
        type: AssetType,
        price: Double,
        marketTime: Long,
        yahooChangePct: Double?,
        previousClose: Double?,
        series: List<PricePoint>,
    ): Double {
        if (yahooChangePct != null && yahooChangePct.isFinite()) return yahooChangePct

        val base = if (type == AssetType.CRYPTO) {
            priceAt(series, marketTime - DAY_SEC) ?: previousClose
        } else {
            previousClose
        }
        return pct(price, base)
    }

    /** Close of the bar nearest to [epochSec], if one lies within the tolerance. */
    fun priceAt(series: List<PricePoint>, epochSec: Long): Double? =
        series.minByOrNull { abs(it.epochSec - epochSec) }
            ?.takeIf { abs(it.epochSec - epochSec) <= TOLERANCE_SEC }
            ?.price

    fun pct(price: Double, base: Double?): Double =
        if (base == null || base == 0.0) 0.0 else (price / base - 1.0) * 100.0
}
