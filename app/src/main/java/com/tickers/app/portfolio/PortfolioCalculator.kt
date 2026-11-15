package com.tickers.app.portfolio

import com.tickers.app.data.PricePoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TradeSide { BUY, SELL }

/** One trade as entered. Amounts are in USD (USDC is treated as 1 USD). */
data class Trade(
    val id: Long,
    val symbol: String,
    val side: TradeSide,
    /** Asset quantity bought (received) or sold. */
    val quantity: Double,
    /** Price per unit in USD. */
    val price: Double,
    /** Fee in USD for the whole trade. */
    val fee: Double,
    val executedAt: Long,
    val note: String = "",
    /** SELL only: true = lots chosen FIFO and recomputed on edits; false = lots chosen by hand. */
    val autoAllocate: Boolean = true,
)

/** [quantity] of buy [buyId] consumed by sell [sellId]. */
data class Allocation(val sellId: Long, val buyId: Long, val quantity: Double)

data class AllocationResult(val allocations: List<Allocation>, val errors: List<String>)

data class OpenLot(
    val buy: Trade,
    val remaining: Double,
    /** Cost per unit including the buy fee. */
    val unitCost: Double,
    val price: Double?,
) {
    val cost get() = remaining * unitCost
    val value get() = price?.let { remaining * it }
    val pnl get() = value?.let { it - cost }
    val pnlPct get() = pnl?.let { if (cost > 0) it / cost * 100 else 0.0 }
}

data class ClosedSale(
    val sell: Trade,
    val proceeds: Double,
    val costBasis: Double,
    /** Quantity-weighted average days the sold lots were held. */
    val holdingDays: Double,
) {
    val pnl get() = proceeds - costBasis
    val pnlPct get() = if (costBasis > 0) pnl / costBasis * 100 else 0.0
}

data class AssetSummary(
    val symbol: String,
    val price: Double?,
    val openLots: List<OpenLot>,
    val sales: List<ClosedSale>,
    val trades: List<Trade>,
) {
    val openQuantity get() = openLots.sumOf { it.remaining }
    val openCost get() = openLots.sumOf { it.cost }
    val value get() = price?.let { openQuantity * it }
    val unrealized get() = value?.let { it - openCost }
    val unrealizedPct get() = unrealized?.let { if (openCost > 0) it / openCost * 100 else 0.0 }
    val realized get() = sales.sumOf { it.pnl }
    val totalPnl get() = (unrealized ?: 0.0) + realized

    private val buys get() = trades.filter { it.side == TradeSide.BUY }
    val totalBought get() = buys.sumOf { it.quantity * it.price + it.fee }

    /** Total P&L relative to everything ever spent on buys. */
    val roiPct get() = if (totalBought > 0) totalPnl / totalBought * 100 else 0.0

    /** Average price paid across all buys (excluding fees), like an exchange shows. */
    val avgBuyPrice get() = buys.sumOf { it.quantity }.let { q ->
        if (q > 0) buys.sumOf { it.quantity * it.price } / q else null
    }

    /** Price at which the open position breaks even (buy fees included, sell fee not). */
    val breakEven get() = if (openQuantity > EPS) openCost / openQuantity else null
    val feesPaid get() = trades.sumOf { it.fee }
    val winRatePct get() = if (sales.isEmpty()) null else sales.count { it.pnl > 0 } * 100.0 / sales.size
    val bestSale get() = sales.maxByOrNull { it.pnl }
    val worstSale get() = sales.minByOrNull { it.pnl }
    val avgHoldingDays get() = sales.sumOf { it.sell.quantity }.let { q ->
        if (q > 0) sales.sumOf { it.holdingDays * it.sell.quantity } / q else null
    }
}

data class SimulatedLot(val lot: OpenLot, val quantity: Double, val proceeds: Double, val cost: Double) {
    val pnl get() = proceeds - cost
}

data class SaleSimulation(val lots: List<SimulatedLot>, val quantity: Double) {
    val proceeds get() = lots.sumOf { it.proceeds }
    val cost get() = lots.sumOf { it.cost }
    val pnl get() = proceeds - cost
    val pnlPct get() = if (cost > 0) pnl / cost * 100 else 0.0
}

data class EquityPoint(val epochSec: Long, val value: Double, val costBasis: Double, val realized: Double) {
    val totalPnl get() = value - costBasis + realized
}

/** Quantities closer than this are considered equal (so 0.1 + 0.2 leaves no dust). */
const val EPS = 1e-9

private const val DAY_MS = 86_400_000.0

/**
 * Pure portfolio math. Each BUY is a lot; each SELL consumes lots either FIFO (auto) or as
 * chosen by hand. Total P&L once everything is sold is the same with any lot choice.
 */
object PortfolioCalculator {

    /** Chronological order; id breaks ties for trades in the same minute. */
    val chronological = compareBy<Trade>({ it.executedAt }, { it.id })

    /**
     * Rebuilds every allocation: manual sells keep [manual] (re-validated), auto sells take
     * the oldest available lots. Any oversell or invalid allocation is reported in errors.
     */
    fun allocate(trades: List<Trade>, manual: List<Allocation>): AllocationResult {
        val errors = mutableListOf<String>()
        val result = mutableListOf<Allocation>()
        val manualBySell = manual.groupBy { it.sellId }

        for ((symbol, list) in trades.groupBy { it.symbol }) {
            val ordered = list.sortedWith(chronological)
            val position = ordered.withIndex().associate { it.value.id to it.index }
            val buys = ordered.filter { it.side == TradeSide.BUY }
            val remaining = buys.associate { it.id to it.quantity }.toMutableMap()

            for (sell in ordered.filter { it.side == TradeSide.SELL }) {
                val sellPos = position.getValue(sell.id)
                val available = buys.filter { position.getValue(it.id) < sellPos }
                val label = "Sell of ${fmt(sell.quantity)} $symbol"

                if (!sell.autoAllocate) {
                    val picks = manualBySell[sell.id].orEmpty()
                    var total = 0.0
                    for (a in picks) {
                        val buy = available.firstOrNull { it.id == a.buyId }
                        val left = remaining[a.buyId]
                        when {
                            buy == null -> errors += "$label uses a buy that is missing or dated after it"
                            a.quantity < -EPS -> errors += "$label has a negative lot quantity"
                            left == null || a.quantity > left + EPS ->
                                errors += "$label takes more from the ${fmt(buy.price)} lot than is left"
                            else -> {
                                remaining[a.buyId] = left - a.quantity
                                total += a.quantity
                                if (a.quantity > EPS) result += a
                            }
                        }
                    }
                    if (abs(total - sell.quantity) > EPS) {
                        errors += "$label: chosen lots add up to ${fmt(total)}"
                    }
                } else {
                    var need = sell.quantity
                    for (buy in available) {
                        if (need <= EPS) break
                        val left = remaining.getValue(buy.id)
                        if (left <= EPS) continue
                        val take = min(left, need)
                        remaining[buy.id] = left - take
                        need -= take
                        result += Allocation(sell.id, buy.id, take)
                    }
                    if (need > EPS) {
                        errors += "$label: only ${fmt(sell.quantity - need)} was owned at that time"
                    }
                }
            }
        }
        return AllocationResult(result, errors)
    }

    fun unitCost(buy: Trade) = if (buy.quantity > 0) (buy.quantity * buy.price + buy.fee) / buy.quantity else 0.0

    fun summarize(
        trades: List<Trade>,
        allocations: List<Allocation>,
        prices: Map<String, Double>,
    ): List<AssetSummary> {
        val byId = trades.associateBy { it.id }
        val used = allocations.groupBy { it.buyId }
        val bySell = allocations.groupBy { it.sellId }

        return trades.groupBy { it.symbol }.map { (symbol, list) ->
            val ordered = list.sortedWith(chronological)
            val price = prices[symbol]
            val lots = ordered.filter { it.side == TradeSide.BUY }.mapNotNull { buy ->
                val left = buy.quantity - used[buy.id].orEmpty().sumOf { it.quantity }
                if (left > EPS) OpenLot(buy, left, unitCost(buy), price) else null
            }
            val sales = ordered.filter { it.side == TradeSide.SELL }.map { sell ->
                val allocs = bySell[sell.id].orEmpty()
                var cost = 0.0
                var dayQty = 0.0
                for (a in allocs) {
                    val buy = byId[a.buyId] ?: continue
                    cost += a.quantity * unitCost(buy)
                    dayQty += a.quantity * max(0.0, (sell.executedAt - buy.executedAt) / DAY_MS)
                }
                ClosedSale(
                    sell = sell,
                    proceeds = sell.quantity * sell.price - sell.fee,
                    costBasis = cost,
                    holdingDays = if (sell.quantity > 0) dayQty / sell.quantity else 0.0,
                )
            }
            AssetSummary(symbol, price, lots, sales, ordered)
        }.sortedByDescending { it.value ?: it.openCost }
    }

    /**
     * What selling [quantity] (default: everything) at [price] would yield, taking lots FIFO.
     * [feePct] is the expected sell fee in percent of the order value.
     */
    fun simulateSale(lots: List<OpenLot>, price: Double, quantity: Double? = null, feePct: Double = 0.0): SaleSimulation {
        var need = quantity ?: lots.sumOf { it.remaining }
        val picked = mutableListOf<SimulatedLot>()
        for (lot in lots.sortedWith(compareBy({ it.buy.executedAt }, { it.buy.id }))) {
            if (need <= EPS) break
            val take = min(lot.remaining, need)
            need -= take
            picked += SimulatedLot(lot, take, take * price * (1 - feePct / 100), take * lot.unitCost)
        }
        return SaleSimulation(picked, picked.sumOf { it.quantity })
    }

    /** Sell price needed for the whole open position to return [targetPct] after a [feePct] sell fee. */
    fun priceForReturn(lots: List<OpenLot>, targetPct: Double, feePct: Double = 0.0): Double? {
        val qty = lots.sumOf { it.remaining }
        if (qty <= EPS) return null
        val cost = lots.sumOf { it.cost }
        return cost * (1 + targetPct / 100) / qty / (1 - feePct / 100)
    }

    /** Daily value vs. cost basis of one asset, from its trades and daily closes. */
    fun equityCurve(
        trades: List<Trade>,
        allocations: List<Allocation>,
        closes: List<PricePoint>,
    ): List<EquityPoint> {
        if (trades.isEmpty() || closes.isEmpty()) return emptyList()
        val byId = trades.associateBy { it.id }
        val first = trades.minOf { it.executedAt } / 1000
        val buys = trades.filter { it.side == TradeSide.BUY }
        val sells = trades.filter { it.side == TradeSide.SELL }
        val bySell = allocations.groupBy { it.sellId }

        return closes.filter { it.epochSec >= first - 86_400 }.map { point ->
            val t = (point.epochSec + 86_400) * 1000 // include trades made during that day
            var qty = 0.0
            var cost = 0.0
            for (b in buys) if (b.executedAt < t) {
                qty += b.quantity
                cost += b.quantity * unitCost(b)
            }
            var realized = 0.0
            for (s in sells) if (s.executedAt < t) {
                qty -= s.quantity
                var matched = 0.0
                for (a in bySell[s.id].orEmpty()) {
                    val buy = byId[a.buyId] ?: continue
                    matched += a.quantity * unitCost(buy)
                }
                cost -= matched
                realized += s.quantity * s.price - s.fee - matched
            }
            EquityPoint(point.epochSec, max(0.0, qty) * point.price, max(0.0, cost), realized)
        }
    }

    /** Parses user input with either decimal separator ("0,1" or "0.1"). */
    fun parseDecimal(text: String): Double? {
        val t = text.trim().replace(" ", "").replace(' '.toString(), "")
        if (t.isEmpty()) return null
        val normalized = when {
            t.contains(',') && t.contains('.') ->
                // Whichever comes last is the decimal separator; the other groups thousands.
                if (t.lastIndexOf(',') > t.lastIndexOf('.')) t.replace(".", "").replace(',', '.')
                else t.replace(",", "")
            else -> t.replace(',', '.')
        }
        return normalized.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun fmt(v: Double) = "%.8f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.')
}
