package com.tickers.app.portfolio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON backup of the portfolio (trades live only on the phone). */
object Backup {
    const val FORMAT = "tickers-portfolio"
    const val VERSION = 1

    @Serializable
    data class File(
        val format: String = FORMAT,
        val version: Int = VERSION,
        val exportedAt: Long,
        val trades: List<TradeJson>,
        val allocations: List<AllocationJson>,
    )

    @Serializable
    data class TradeJson(
        val id: Long,
        val symbol: String,
        val side: String,
        val quantity: Double,
        val price: Double,
        val fee: Double,
        val quoteCurrency: String = "USDC",
        val executedAt: Long,
        val note: String = "",
        val autoAllocate: Boolean = true,
    )

    @Serializable
    data class AllocationJson(val sellId: Long, val buyId: Long, val quantity: Double)

    data class Decoded(val trades: List<Pair<Trade, String>>, val allocations: List<Allocation>)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun encode(trades: List<Pair<Trade, String>>, allocations: List<Allocation>, now: Long = System.currentTimeMillis()): String =
        json.encodeToString(
            File.serializer(),
            File(
                exportedAt = now,
                trades = trades.map { (t, q) ->
                    TradeJson(t.id, t.symbol, t.side.name, t.quantity, t.price, t.fee, q, t.executedAt, t.note, t.autoAllocate)
                },
                allocations = allocations.map { AllocationJson(it.sellId, it.buyId, it.quantity) },
            ),
        )

    fun decode(text: String): Decoded {
        val file = try {
            json.decodeFromString(File.serializer(), text)
        } catch (e: Exception) {
            throw PortfolioException("Not a Tickers backup file")
        }
        if (file.format != FORMAT) throw PortfolioException("Not a Tickers backup file")
        if (file.version > VERSION) throw PortfolioException("Backup is from a newer app version; update first")
        val trades = file.trades.map { t ->
            val side = TradeSide.entries.firstOrNull { it.name == t.side }
                ?: throw PortfolioException("Bad trade side: ${t.side}")
            Trade(t.id, t.symbol, side, t.quantity, t.price, t.fee, t.executedAt, t.note, t.autoAllocate) to t.quoteCurrency
        }
        return Decoded(trades, file.allocations.map { Allocation(it.sellId, it.buyId, it.quantity) })
    }
}
