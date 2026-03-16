package dev.ambon.persistence

import com.fasterxml.jackson.core.type.TypeReference
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

private val wsMapper = jsonMapper

private val strStrMapType = object : TypeReference<Map<String, String>>() {}
private val strListStrMapType = object : TypeReference<Map<String, List<String>>>() {}

class PostgresWorldStateRepository(
    private val database: Database,
) : WorldStateRepository {
    override suspend fun load(): WorldStateSnapshot? =
        database.dbQuery {
            WorldStateTable
                .selectAll()
                .where { WorldStateTable.id eq "singleton" }
                .firstOrNull()
                ?.let { row ->
                    WorldStateSnapshot(
                        doorStates =
                            runCatching {
                                wsMapper.readValue(row[WorldStateTable.doorStates], strStrMapType)
                            }.getOrDefault(emptyMap()),
                        containerStates =
                            runCatching {
                                wsMapper.readValue(row[WorldStateTable.containerStates], strStrMapType)
                            }.getOrDefault(emptyMap()),
                        leverStates =
                            runCatching {
                                wsMapper.readValue(row[WorldStateTable.leverStates], strStrMapType)
                            }.getOrDefault(emptyMap()),
                        containerItems =
                            runCatching {
                                wsMapper.readValue(row[WorldStateTable.containerItems], strListStrMapType)
                            }.getOrDefault(emptyMap()),
                    )
                }
        }

    override suspend fun save(snapshot: WorldStateSnapshot) {
        database.dbQuery {
            WorldStateTable.upsert(WorldStateTable.id) {
                it[id] = "singleton"
                it[doorStates] = wsMapper.writeValueAsString(snapshot.doorStates)
                it[containerStates] = wsMapper.writeValueAsString(snapshot.containerStates)
                it[leverStates] = wsMapper.writeValueAsString(snapshot.leverStates)
                it[containerItems] = wsMapper.writeValueAsString(snapshot.containerItems)
            }
        }
    }
}
