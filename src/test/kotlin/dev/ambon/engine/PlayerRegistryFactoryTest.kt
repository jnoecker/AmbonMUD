package dev.ambon.engine

import dev.ambon.config.EngineConfig
import dev.ambon.domain.PlayerClass
import dev.ambon.domain.Race
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
                    race = Race.HUMAN,
                    playerClass = PlayerClass.WARRIOR,
                )

            assertEquals(CreateResult.Ok, result)
            val player = registry.get(SessionId(1))
            assertNotNull(player)
            assertEquals(warriorRoom, player!!.roomId)
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
                    Race.HUMAN,
                    PlayerClass.WARRIOR,
                )

            assertEquals(
                CreateAccountPrep.Taken,
                result,
                "prepareCreateAccount should return Taken when repo.create throws PlayerPersistenceException",
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
                    race = Race.HUMAN,
                    playerClass = PlayerClass.WARRIOR,
                )

            assertEquals(
                CreateResult.Taken,
                result,
                "create should return Taken when repo.create throws PlayerPersistenceException",
            )
        }
}
