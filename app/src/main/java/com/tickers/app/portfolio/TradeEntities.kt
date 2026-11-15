package com.tickers.app.portfolio

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trades")
data class TradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val side: TradeSide,
    val quantity: Double,
    val price: Double,
    val fee: Double,
    /** What was paid / received, e.g. USDC. Informational; valued as USD. */
    val quoteCurrency: String,
    val executedAt: Long,
    val note: String,
    val autoAllocate: Boolean,
) {
    fun toTrade() = Trade(id, symbol, side, quantity, price, fee, executedAt, note, autoAllocate)
}

fun Trade.toEntity(quoteCurrency: String = "USDC") =
    TradeEntity(id, symbol, side, quantity, price, fee, quoteCurrency, executedAt, note, autoAllocate)

@Entity(
    tableName = "sell_allocations",
    primaryKeys = ["sellTradeId", "buyTradeId"],
    indices = [Index("buyTradeId")],
)
data class SellAllocationEntity(
    val sellTradeId: Long,
    val buyTradeId: Long,
    val quantity: Double,
) {
    fun toAllocation() = Allocation(sellTradeId, buyTradeId, quantity)
}

fun Allocation.toEntity() = SellAllocationEntity(sellId, buyId, quantity)

@Dao
interface TradeDao {
    @Query("SELECT * FROM trades ORDER BY executedAt, id")
    fun observeTrades(): Flow<List<TradeEntity>>

    @Query("SELECT * FROM trades ORDER BY executedAt, id")
    suspend fun trades(): List<TradeEntity>

    @Query("SELECT * FROM trades WHERE id = :id")
    suspend fun trade(id: Long): TradeEntity?

    @Query("SELECT DISTINCT symbol FROM trades")
    suspend fun symbols(): List<String>

    @Query("SELECT DISTINCT symbol FROM trades")
    fun observeSymbols(): Flow<List<String>>

    @Upsert
    suspend fun upsert(trade: TradeEntity): Long

    @Query("DELETE FROM trades WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM trades")
    suspend fun deleteAllTrades()

    @Insert
    suspend fun insertTrades(trades: List<TradeEntity>)

    @Query("SELECT * FROM sell_allocations")
    fun observeAllocations(): Flow<List<SellAllocationEntity>>

    @Query("SELECT * FROM sell_allocations")
    suspend fun allocations(): List<SellAllocationEntity>

    @Query("DELETE FROM sell_allocations")
    suspend fun deleteAllAllocations()

    @Insert
    suspend fun insertAllocations(allocations: List<SellAllocationEntity>)
}
