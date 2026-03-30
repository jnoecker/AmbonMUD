package dev.ambon.persistence

import dev.ambon.domain.housing.HouseRecord
import dev.ambon.domain.housing.HouseRoomRecord
import dev.ambon.domain.world.Direction
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class YamlHouseRepositoryTest {
    @TempDir
    lateinit var tmp: Path

    private val ownerId = PlayerId(42L)

    private fun makeRecord(
        ownerName: String = "Gandalf",
    ) = HouseRecord(
        ownerId = ownerId,
        ownerName = ownerName,
        rooms = listOf(
            HouseRoomRecord(
                templateId = "cottage_entry",
                exits = mapOf(Direction.NORTH to 1),
            ),
            HouseRoomRecord(
                templateId = "vault",
                exits = mapOf(Direction.SOUTH to 0),
            ),
        ),
        createdAtEpochMs = 1000L,
    )

    @Test
    fun `save then findByOwnerId round-trips house`() =
        runTest {
            val repo = YamlHouseRepository(tmp)
            repo.save(makeRecord())

            val found = repo.findByOwnerId(ownerId)
            assertNotNull(found)
            assertEquals("Gandalf", found!!.ownerName)
            assertEquals(2, found.rooms.size)
            assertEquals("cottage_entry", found.rooms[0].templateId)
            assertEquals(mapOf(Direction.NORTH to 1), found.rooms[0].exits)
            assertEquals("vault", found.rooms[1].templateId)
            assertEquals(mapOf(Direction.SOUTH to 0), found.rooms[1].exits)
        }

    @Test
    fun `save overwrites existing record`() =
        runTest {
            val repo = YamlHouseRepository(tmp)
            repo.save(makeRecord())

            val updated = makeRecord().copy(
                rooms = listOf(
                    HouseRoomRecord(templateId = "cottage_entry"),
                ),
            )
            repo.save(updated)

            val loaded = repo.findByOwnerId(ownerId)!!
            assertEquals(1, loaded.rooms.size)
        }

    @Test
    fun `findByOwnerName is case-insensitive`() =
        runTest {
            val repo = YamlHouseRepository(tmp)
            repo.save(makeRecord())

            assertNotNull(repo.findByOwnerName("Gandalf"))
            assertNotNull(repo.findByOwnerName("gandalf"))
            assertNotNull(repo.findByOwnerName("GANDALF"))
            assertNull(repo.findByOwnerName("Frodo"))
        }

    @Test
    fun `delete removes house file`() =
        runTest {
            val repo = YamlHouseRepository(tmp)
            repo.save(makeRecord())
            assertNotNull(repo.findByOwnerId(ownerId))

            repo.delete(ownerId)
            assertNull(repo.findByOwnerId(ownerId))
        }

    @Test
    fun `findByOwnerId returns null for unknown`() =
        runTest {
            val repo = YamlHouseRepository(tmp)
            assertNull(repo.findByOwnerId(PlayerId(999L)))
        }

    @Test
    fun `custom title and description round-trip`() =
        runTest {
            val repo = YamlHouseRepository(tmp)
            val record = HouseRecord(
                ownerId = ownerId,
                ownerName = "Gandalf",
                rooms = listOf(
                    HouseRoomRecord(
                        templateId = "cottage_entry",
                        customTitle = "My Cozy Cabin",
                        customDescription = "Gandalf's favourite reading nook.",
                    ),
                ),
                createdAtEpochMs = 2000L,
            )
            repo.save(record)

            val loaded = repo.findByOwnerId(ownerId)!!
            assertEquals("My Cozy Cabin", loaded.rooms[0].customTitle)
            assertEquals("Gandalf's favourite reading nook.", loaded.rooms[0].customDescription)
        }
}
