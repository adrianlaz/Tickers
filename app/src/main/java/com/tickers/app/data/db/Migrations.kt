package com.tickers.app.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Hand-written migrations. Each statement is copied verbatim from the exported Room schema
 * (app/schemas/.../N.json) because Room validates the tables on open and crashes on any mismatch.
 * MigrationTest checks these strings against the schema files.
 */
object Migrations {

    /** v1 -> v2: portfolio tables. The watchlist tables are unchanged. */
    val V1_TO_V2_SQL = listOf(
        "CREATE TABLE IF NOT EXISTS `trades` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `symbol` TEXT NOT NULL, `side` TEXT NOT NULL, `quantity` REAL NOT NULL, `price` REAL NOT NULL, `fee` REAL NOT NULL, `quoteCurrency` TEXT NOT NULL, `executedAt` INTEGER NOT NULL, `note` TEXT NOT NULL, `autoAllocate` INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS `sell_allocations` (`sellTradeId` INTEGER NOT NULL, `buyTradeId` INTEGER NOT NULL, `quantity` REAL NOT NULL, PRIMARY KEY(`sellTradeId`, `buyTradeId`))",
        "CREATE INDEX IF NOT EXISTS `index_sell_allocations_buyTradeId` ON `sell_allocations` (`buyTradeId`)",
    )

    val V1_TO_V2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            V1_TO_V2_SQL.forEach(db::execSQL)
        }
    }

    val ALL = arrayOf(V1_TO_V2)
}
