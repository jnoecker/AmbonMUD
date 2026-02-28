package dev.ambon.persistence

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class PostgresWorldContentRepository(
    private val database: Database,
) {
    fun loadAll(): List<WorldContentDocument> =
        transaction(database) {
            WorldContentTable
                .selectAll()
                .orderBy(WorldContentTable.loadOrder to SortOrder.ASC)
                .map { row ->
                    WorldContentDocument(
                        sourceName = row[WorldContentTable.sourceName],
                        zone = row[WorldContentTable.zone],
                        content = row[WorldContentTable.content],
                        loadOrder = row[WorldContentTable.loadOrder],
                        importedAtEpochMs = row[WorldContentTable.importedAtEpochMs],
                    )
                }
        }

    fun replaceAll(documents: List<WorldContentDocument>) {
        transaction(database) {
            WorldContentTable.deleteAll()
            documents.forEach { document ->
                WorldContentTable.insert {
                    it[sourceName] = document.sourceName
                    it[zone] = document.zone
                    it[content] = document.content
                    it[loadOrder] = document.loadOrder
                    it[importedAtEpochMs] = document.importedAtEpochMs
                }
            }
        }
    }
}
