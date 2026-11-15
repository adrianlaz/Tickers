package com.tickers.app.ui

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/** Shared number formatting for the app and the widgets. */
object Format {
    private val symbols = DecimalFormatSymbols(Locale.US)

    fun price(value: Double?, currency: String? = "USD"): String {
        if (value == null) return "—"
        val pattern = when {
            abs(value) >= 1000 -> "#,##0.##"
            abs(value) >= 1 -> "#,##0.00"
            abs(value) >= 0.01 -> "0.0000"
            else -> "0.00######"
        }
        val number = DecimalFormat(pattern, symbols).format(value)
        return when (currency) {
            null, "USD" -> "$$number"
            "EUR" -> "€$number"
            "GBP" -> "£$number"
            else -> "$number $currency"
        }
    }

    /** Narrower price for tight widget cells: no cents at or above 1,000. */
    fun priceCompact(value: Double?, currency: String? = "USD"): String =
        if (value != null && abs(value) >= 1000) {
            val number = DecimalFormat("#,##0", symbols).format(value)
            if (currency == null || currency == "USD") "$$number" else price(value, currency)
        } else {
            price(value, currency)
        }

    fun change(pct: Double?): String {
        if (pct == null) return "—"
        val arrow = if (pct >= 0) "▲" else "▼"
        return "$arrow ${DecimalFormat("0.00", symbols).format(abs(pct))}%"
    }

    /** Asset quantity, e.g. "0.15 ETH" (up to 8 decimals, no trailing zeros). */
    fun qty(value: Double, symbol: String): String =
        "${DecimalFormat("#,##0.########", symbols).format(value)} ${ticker(symbol)}"

    /** Plain number for text fields, e.g. 0.15 or 2000 (no grouping). */
    fun plain(value: Double): String = DecimalFormat("0.########", symbols).format(value)

    /** Money with cents, e.g. "$1,234.56" / "-$5.00". */
    fun usd(value: Double?): String {
        if (value == null) return "—"
        val n = DecimalFormat("#,##0.00", symbols).format(abs(value))
        return if (value < 0) "-$$n" else "$$n"
    }

    /** P&L with explicit sign, e.g. "+$12.30" / "-$5.00". */
    fun signedUsd(value: Double?): String {
        if (value == null) return "—"
        return if (value >= 0) "+${usd(value)}" else usd(value)
    }

    /** Percent with explicit sign, e.g. "+3.21%". */
    fun signedPct(value: Double?): String {
        if (value == null) return "—"
        val n = DecimalFormat("0.00", symbols).format(abs(value))
        return if (value >= 0) "+$n%" else "-$n%"
    }

    /** Short display symbol: "BTC-USD" -> "BTC". */
    fun ticker(symbol: String): String = symbol.removeSuffix("-USD")
}
