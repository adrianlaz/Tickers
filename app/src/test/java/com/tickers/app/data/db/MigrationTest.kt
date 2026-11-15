package com.tickers.app.data.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Room crashes at startup if a migrated table differs from what it expects. This checks the
 * hand-written migration SQL against the schema Room itself exported.
 */
class MigrationTest {

    private fun schemaSql(version: Int, tables: Set<String>): List<String> {
        val file = File("schemas/com.tickers.app.data.db.TickersDatabase/$version.json")
        val db = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
        return db.getValue("entities").jsonArray.map { it.jsonObject }
            .filter { it.getValue("tableName").jsonPrimitive.content in tables }
            .flatMap { e ->
                val table = e.getValue("tableName").jsonPrimitive.content
                val create = e.getValue("createSql").jsonPrimitive.content
                val indices = e["indices"]?.jsonArray.orEmpty()
                    .map { it.jsonObject.getValue("createSql").jsonPrimitive.content }
                (listOf(create) + indices).map { it.replace("\${TABLE_NAME}", table) }
            }
    }

    @Test
    fun v1ToV2_matchesExportedSchema() {
        assertEquals(schemaSql(2, setOf("trades", "sell_allocations")), Migrations.V1_TO_V2_SQL)
    }

    @Test
    fun v2_keepsWatchlistTablesAsInV1() {
        // v1 had no exported schema; these are the tables 0.1/0.2 created. They must not change.
        assertEquals(
            listOf(
                "CREATE TABLE IF NOT EXISTS `assets` (`symbol` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`symbol`))",
                "CREATE TABLE IF NOT EXISTS `quotes` (`symbol` TEXT NOT NULL, `price` REAL NOT NULL, `changePct` REAL NOT NULL, `currency` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`symbol`))",
            ),
            schemaSql(2, setOf("assets", "quotes")),
        )
    }
}
