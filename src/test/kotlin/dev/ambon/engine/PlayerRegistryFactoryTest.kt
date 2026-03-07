package dev.ambon.engine

import dev.ambon.config.EngineConfig
import dev.ambon.config.StatsEngineConfig
import dev.ambon.domain.StatDefinition
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.persistence.PlayerCreationRequest
import dev.ambon.persistence.PlayerId
import dev.ambon.persistence.PlayerRecord
import dev.ambon.persistence.PlayerRepository
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.Clock

class PlayerRegistryFactoryTest {
    @Test
    fun `createPlayerRegistry applies configured class start rooms`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val warriorRoom = RoomId("test_zone:outpost")
            val repo = InMemoryPlayerRepository()
            val items = ItemRegistry()
            val registry =
                createPlayerRegistry(
                    startRoom = world.startRoom,
                    engineConfig =
                        EngineConfig(
                            classStartRooms =
                                mapOf(
                                    "WARRIOR" to warriorRoom.value,
                                ),
                        ),
                    repo = repo,
                    items = items,
                    clock = Clock.systemUTC(),
                    progression = PlayerProgression(),
                    hashingContext = coroutineContext,
                )

            val result =
                registry.create(
                    sessionId = SessionId(1),
                    nameRaw = "WarriorSpawn",
                    passwordRaw = "password",
                    race = "HUMAN",
                    playerClass = "WARRIOR",
                )

            assertEquals(CreateResult.Ok, result)
            val player = registry.get(SessionId(1))
            assertNotNull(player)
            assertEquals(warriorRoom, player!!.roomId)
        }

    @Test
    fun `createPlayerRegistry uses statRegistry baseStat for new character stats`() =
        runTest {
            val world = dev.ambon.test.TestWorlds.testWorld
            val repo = InMemoryPlayerRepository()
            val items = ItemRegistry()
            val statRegistry =
                StatRegistry().also { reg ->
                    listOf("STR", "DEX", "CON", "INT", "WIS", "CHA").forEach { id ->
                        reg.register(StatDefinition(id = id, displayName = id, abbreviation = id, baseStat = 12))
                    }
                }
            val registry =
                createPlayerRegistry(
                    startRoom = world.startRoom,
                    engineConfig = EngineConfig(stats = StatsEngineConfig()),
                    repo = repo,
                    items = items,
                    clock = Clock.systemUTC(),
                    progression = PlayerProgression(),
                    hashingContext = coroutineContext,
                    statRegistry = statRegistry,
                )

            registry.create(SessionId(1), "Tester", "password")
            val player = registry.get(SessionId(1))
            assertNotNull(player)
            assertEquals(12, player!!.stats["STR"], "STR should use statRegistry baseStat of 12")
            assertEquals(12, player.stats["DEX"], "DEX should use statRegistry baseStat of 12")
            assertEquals(12, player.stats["CON"], "CON should use statRegistry baseStat of 12")
            assertEquals(12, player.stats["INT"], "INT should use statRegistry baseStat of 12")
            assertEquals(12, player.stats["WIS"], "WIS should use statRegistry baseStat of 12")
            assertEquals(12, player.stats["CHA"], "CHA should use statRegistry baseStat of 12")
        }

    @Test
    fun `prepareCreateAccount returns Taken when repo throws duplicate name`() =
        runTest {
            val startRoom = dev.ambon.test.TestWorlds.testWorld.startRoom
            val inner = InMemoryPlayerRepository()

            // Pre-populate "Alice" in the underlying repo
            inner.create(
                PlayerCreationRequest(
                    name = "Alice",
                    startRoomId = startRoom,
                    nowEpochMs = 0L,
                    passwordHash = "hash",
                    ansiEnabled = false,
                ),
            )

            // Wrap repo so findByName always misses — simulating the TOCTOU race window
            val racyRepo =
                object : PlayerRepository {
                    override suspend fun findByName(name: String): PlayerRecord? = null

                    override suspend fun findById(id: PlayerId): PlayerRecord? = inner.findById(id)

                    override suspend fun create(request: PlayerCreationRequest): PlayerRecord = inner.create(request)

                    override suspend fun save(record: PlayerRecord) = inner.save(record)
                }

            val registry =
                dev.ambon.test.buildTestPlayerRegistry(startRoom, racyRepo, ItemRegistry())

            val result =
                registry.prepareCreateAccount(
                    "Alice",
                    "password",
                    false,
                    "HUMAN",
                    "WARRIOR",
                )

            assertEquals(
                CreateAccountPrep.Taken,
                result,
                "prepareCreateAccount should return Taken when repo.create throws PersistenceException",
            )
        }

    @Test
    fun `create returns Taken when repo throws duplicate name`() =
        runTest {
            val startRoom = dev.ambon.test.TestWorlds.testWorld.startRoom
            val inner = InMemoryPlayerRepository()

            // Pre-populate "Alice" in the underlying repo
            inner.create(
                PlayerCreationRequest(
                    name = "Alice",
                    startRoomId = startRoom,
                    nowEpochMs = 0L,
                    passwordHash = "hash",
                    ansiEnabled = false,
                ),
            )

            // Wrap repo so findByName always misses — simulating the TOCTOU race window
            val racyRepo =
                object : PlayerRepository {
                    override suspend fun findByName(name: String): PlayerRecord? = null

                    override suspend fun findById(id: PlayerId): PlayerRecord? = inner.findById(id)

                    override suspend fun create(request: PlayerCreationRequest): PlayerRecord = inner.create(request)

                    override suspend fun save(record: PlayerRecord) = inner.save(record)
                }

            val registry =
                dev.ambon.test.buildTestPlayerRegistry(startRoom, racyRepo, ItemRegistry())

            val result =
                registry.create(
                    sessionId = SessionId(1),
                    nameRaw = "Alice",
                    passwordRaw = "password",
                    race = "HUMAN",
                    playerClass = "WARRIOR",
                )

            assertEquals(
                CreateResult.Taken,
                result,
                "create should return Taken when repo.create throws PersistenceException",
            )
        }
}
