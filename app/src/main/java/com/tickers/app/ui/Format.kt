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

    fun change(pct: Double?): String {
        if (pct == null) return "—"
        val arrow = if (pct >= 0) "▲" else "▼"
        return "$arrow ${DecimalFormat("0.00", symbols).format(abs(pct))}%"
    }

    /** Short display symbol: "BTC-USD" -> "BTC". */
    fun ticker(symbol: String): String = symbol.removeSuffix("-USD")
}
