package com.tickers.app.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import com.tickers.app.data.AssetType
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val type: AssetType,
    val sortOrder: Int,
)

@Entity(tableName = "quotes")
data class QuoteEntity(
    @PrimaryKey val symbol: String,
    val price: Double,
    val changePct: Double,
    val currency: String,
    /** Epoch millis when this quote was fetched. */
    val updatedAt: Long,
)

/** Row shown in the app list and the widgets: an asset plus its cached quote, if any. */
data class WatchItem(
    val symbol: String,
    val name: String,
    val type: AssetType,
    val sortOrder: Int,
    val price: Double?,
    val changePct: Double?,
    val currency: String?,
    val updatedAt: Long?,
)

@Dao
interface AssetDao {
    @Query(
        """SELECT a.symbol, a.name, a.type, a.sortOrder, q.price, q.changePct, q.currency, q.updatedAt
           FROM assets a LEFT JOIN quotes q ON q.symbol = a.symbol
           ORDER BY a.sortOrder"""
    )
    fun observeWatchlist(): Flow<List<WatchItem>>

    @Query(
        """SELECT a.symbol, a.name, a.type, a.sortOrder, q.price, q.changePct, q.currency, q.updatedAt
           FROM assets a LEFT JOIN quotes q ON q.symbol = a.symbol
           ORDER BY a.sortOrder"""
    )
    suspend fun watchlist(): List<WatchItem>

    @Query("SELECT * FROM assets ORDER BY sortOrder")
    suspend fun assets(): List<AssetEntity>

    @Query("SELECT COUNT(*) FROM assets")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM assets")
    suspend fun maxSortOrder(): Int

    @Upsert
    suspend fun upsertAssets(assets: List<AssetEntity>)

    @Upsert
    suspend fun upsertQuote(quote: QuoteEntity)

    /** Quotes are kept after an asset is removed, since a Ticker widget may still show it. */
    @Query("DELETE FROM assets WHERE symbol = :symbol")
    suspend fun deleteAsset(symbol: String)

    @Query("SELECT * FROM quotes WHERE symbol = :symbol")
    fun observeQuote(symbol: String): Flow<QuoteEntity?>

    @Query("SELECT * FROM quotes WHERE symbol = :symbol")
    suspend fun quote(symbol: String): QuoteEntity?
}

class Converters {
    @TypeConverter fun fromType(t: AssetType): String = t.name
    @TypeConverter fun toType(s: String): AssetType =
        runCatching { AssetType.valueOf(s) }.getOrDefault(AssetType.OTHER)
}

@Database(entities = [AssetEntity::class, QuoteEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class TickersDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao
}
