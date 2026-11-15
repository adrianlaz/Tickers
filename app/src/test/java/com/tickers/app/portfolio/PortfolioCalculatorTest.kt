package com.tickers.app.portfolio

import com.tickers.app.data.PricePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PortfolioCalculatorTest {

    private val day = 86_400_000L
    private val t0 = 1_790_000_000_000L

    private fun buy(id: Long, qty: Double, price: Double, d: Int, fee: Double = 0.0) =
        Trade(id, "ETH-USD", TradeSide.BUY, qty, price, fee, t0 + d * day)

    private fun sell(id: Long, qty: Double, price: Double, d: Int, fee: Double = 0.0, auto: Boolean = true) =
        Trade(id, "ETH-USD", TradeSide.SELL, qty, price, fee, t0 + d * day, autoAllocate = auto)

    /** The user's example: 0.1 @ 2000, 0.1 @ 1900, 0.2 @ 1800. */
    private val buys = listOf(buy(1, 0.1, 2000.0, 0), buy(2, 0.1, 1900.0, 1), buy(3, 0.2, 1800.0, 2))

    private fun summary(trades: List<Trade>, manual: List<Allocation> = emptyList(), price: Double = 2100.0): AssetSummary {
        val alloc = PortfolioCalculator.allocate(trades, manual)
        assertTrue(alloc.errors.toString(), alloc.errors.isEmpty())
        return PortfolioCalculator.summarize(trades, alloc.allocations, mapOf("ETH-USD" to price)).single()
    }

    @Test
    fun openPosition_unrealizedPerLot() {
        val s = summary(buys)
        assertEquals(0.4, s.openQuantity, 1e-12)
        assertEquals(750.0, s.openCost, 1e-9)
        assertEquals(840.0, s.value!!, 1e-9)
        assertEquals(90.0, s.unrealized!!, 1e-9)
        assertEquals(1875.0, s.avgBuyPrice!!, 1e-9)
        assertEquals(1875.0, s.breakEven!!, 1e-9)
        // Lot from 1800: 0.2 * (2100 - 1800) = +60.
        assertEquals(60.0, s.openLots.single { it.buy.id == 3L }.pnl!!, 1e-9)
    }

    @Test
    fun fifoSell_matchesOldestLot_tableFromPlan() {
        val s = summary(buys + sell(10, 0.1, 2100.0, 3))
        assertEquals(10.0, s.realized, 1e-9) // 0.1 * (2100 - 2000)
        assertEquals(550.0, s.openCost, 1e-9)
        assertEquals(1833.333333, s.breakEven!!, 1e-6)
        assertEquals(80.0, s.unrealized!!, 1e-9) // 630 - 550
        assertEquals(90.0, s.totalPnl, 1e-9)
    }

    @Test
    fun totalPnl_isSameWhicheverLotsAreSold() {
        val fifo = summary(buys + sell(10, 0.1, 2100.0, 3))
        val manual = summary(
            buys + sell(10, 0.1, 2100.0, 3, auto = false),
            manual = listOf(Allocation(10, 3, 0.1)),
        )
        assertEquals(30.0, manual.realized, 1e-9) // sold from the 1800 lot
        assertEquals(fifo.totalPnl, manual.totalPnl, 1e-9)
        val everything = summary(buys + sell(10, 0.1, 2100.0, 3) + sell(11, 0.3, 2100.0, 4))
        assertEquals(90.0, everything.realized, 1e-9)
        assertEquals(0.0, everything.openQuantity, 1e-12)
    }

    @Test
    fun fees_raiseCostAndLowerProceeds() {
        val s = summary(listOf(buy(1, 0.1, 2000.0, 0, fee = 1.0), sell(2, 0.1, 2100.0, 1, fee = 1.0)))
        assertEquals(8.0, s.realized, 1e-9) // 209 - 201
        assertEquals(2.0, s.feesPaid, 1e-9)
    }

    @Test
    fun partialSells_floatDustDoesNotLeaveLots() {
        val s = summary(listOf(buy(1, 0.3, 2000.0, 0), sell(2, 0.1, 2000.0, 1), sell(3, 0.2, 2000.0, 2)))
        assertTrue(s.openLots.isEmpty())
    }

    @Test
    fun oversell_isRejected() {
        val r = PortfolioCalculator.allocate(buys + sell(10, 0.5, 2100.0, 3), emptyList())
        assertEquals(1, r.errors.size)
    }

    @Test
    fun sellBeforeBuy_cannotUseIt() {
        val trades = listOf(buy(1, 0.1, 2000.0, 5), sell(2, 0.1, 2100.0, 3))
        assertEquals(1, PortfolioCalculator.allocate(trades, emptyList()).errors.size)
        val manual = PortfolioCalculator.allocate(
            listOf(buy(1, 0.1, 2000.0, 5), sell(2, 0.1, 2100.0, 3, auto = false)),
            listOf(Allocation(2, 1, 0.1)),
        )
        assertTrue(manual.errors.isNotEmpty())
    }

    @Test
    fun backdatedBuy_reallocatesAutoSells() {
        // Sell on day 3 took the 2000 lot; a buy back-dated to day -1 becomes the oldest lot.
        val s = summary(buys + sell(10, 0.1, 2100.0, 3) + buy(4, 0.1, 1500.0, -1))
        assertEquals(60.0, s.realized, 1e-9) // 0.1 * (2100 - 1500)
    }

    @Test
    fun manualAllocation_exceedingLot_isRejected() {
        val r = PortfolioCalculator.allocate(
            buys + sell(10, 0.3, 2100.0, 3, auto = false),
            listOf(Allocation(10, 1, 0.3)),
        )
        assertTrue(r.errors.isNotEmpty())
    }

    @Test
    fun winRate_bestWorst_holding() {
        val s = summary(buys + sell(10, 0.1, 2100.0, 3) + sell(11, 0.1, 1800.0, 4))
        // FIFO: first sale +10 (2000 lot), second -10 (1900 lot).
        assertEquals(50.0, s.winRatePct!!, 1e-9)
        assertEquals(10.0, s.bestSale!!.pnl, 1e-9)
        assertEquals(-10.0, s.worstSale!!.pnl, 1e-9)
        assertEquals(3.0, s.avgHoldingDays!!, 1e-9) // 3 days each
    }

    @Test
    fun simulateSale_fifoAndTargetPrice() {
        val lots = summary(buys).openLots
        val sim = PortfolioCalculator.simulateSale(lots, price = 2100.0, quantity = 0.15)
        assertEquals(2, sim.lots.size) // all of the 2000 lot + half of the 1900 lot
        assertEquals(10.0 + 10.0, sim.pnl, 1e-9)
        val all = PortfolioCalculator.simulateSale(lots, price = 2100.0)
        assertEquals(90.0, all.pnl, 1e-9)
        // +10% on 750 cost over 0.4 ETH -> 2062.5
        assertEquals(2062.5, PortfolioCalculator.priceForReturn(lots, 10.0)!!, 1e-9)
        assertNull(PortfolioCalculator.priceForReturn(emptyList(), 10.0))
    }

    @Test
    fun equityCurve_tracksValueCostAndRealized() {
        val trades = listOf(buy(1, 0.1, 2000.0, 0), sell(2, 0.05, 2200.0, 2))
        val alloc = PortfolioCalculator.allocate(trades, emptyList()).allocations
        val closes = (0..3).map { PricePoint((t0 + it * day) / 1000, 2000.0 + it * 100) }
        val curve = PortfolioCalculator.equityCurve(trades, alloc, closes)
        assertEquals(4, curve.size)
        assertEquals(200.0, curve[0].value, 1e-9)
        assertEquals(200.0, curve[0].costBasis, 1e-9)
        assertEquals(0.05 * 2300.0, curve.last().value, 1e-9)
        assertEquals(100.0, curve.last().costBasis, 1e-9)
        assertEquals(10.0, curve.last().realized, 1e-9)
    }

    @Test
    fun parseDecimal_acceptsCommaAndDot() {
        assertEquals(0.1, PortfolioCalculator.parseDecimal("0,1")!!, 0.0)
        assertEquals(0.1, PortfolioCalculator.parseDecimal(" 0.1 ")!!, 0.0)
        assertEquals(1234.5, PortfolioCalculator.parseDecimal("1.234,5")!!, 0.0)
        assertEquals(1234.5, PortfolioCalculator.parseDecimal("1,234.5")!!, 0.0)
        assertNull(PortfolioCalculator.parseDecimal("abc"))
        assertNull(PortfolioCalculator.parseDecimal(""))
    }
}
