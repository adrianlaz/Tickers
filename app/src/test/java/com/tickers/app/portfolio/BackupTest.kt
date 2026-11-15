package com.tickers.app.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupTest {

    @Test
    fun roundTrip_keepsEverything() {
        val trades = listOf(
            Trade(1, "ETH-USD", TradeSide.BUY, 0.1, 2000.0, 0.18, 1_790_000_000_000, "first") to "USDC",
            Trade(2, "ETH-USD", TradeSide.SELL, 0.05, 2100.0, 0.0, 1_790_100_000_000, autoAllocate = false) to "USDC",
        )
        val allocations = listOf(Allocation(2, 1, 0.05))
        val decoded = Backup.decode(Backup.encode(trades, allocations, now = 1L))
        assertEquals(trades, decoded.trades)
        assertEquals(allocations, decoded.allocations)
    }

    @Test
    fun rejectsOtherJson() {
        assertThrows(PortfolioException::class.java) { Backup.decode("""{"hello": 1}""") }
        assertThrows(PortfolioException::class.java) { Backup.decode("not json") }
        assertThrows(PortfolioException::class.java) {
            Backup.decode("""{"format":"tickers-portfolio","version":99,"exportedAt":0,"trades":[],"allocations":[]}""")
        }
    }
}
