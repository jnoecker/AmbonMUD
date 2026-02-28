package dev.ambon.engine

import dev.ambon.config.EngineConfig
import dev.ambon.domain.PlayerClass
import dev.ambon.domain.Race
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
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
}
