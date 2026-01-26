package dev.ambon.domain.world.load

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorldLoaderTest {
    @Test
    fun `loads a valid small world and wires exits`() {
        val world = WorldLoader.loadFromResource("world/ok_small.yaml")
        val zone = "ok_small"

        val aId = RoomId("$zone:a")
        val bId = RoomId("$zone:b")

        assertEquals(aId, world.startRoom)
        assertTrue(world.rooms.containsKey(aId))
        assertTrue(world.rooms.containsKey(bId))

        val a = world.rooms.getValue(aId)
        val b = world.rooms.getValue(bId)

        assertEquals("Room A", a.title)
        assertEquals("Room B", b.title)

        assertEquals(bId, a.exits[Direction.NORTH])
        assertEquals(aId, b.exits[Direction.SOUTH])
    }

    @Test
    fun `loads mobs from a zone file`() {
        val world = WorldLoader.loadFromResource("world/ok_small.yaml")

        val mob = world.mobSpawns.single()
        assertEquals("ok_small:rat", mob.id.value)
        assertEquals("a small rat", mob.name)
        assertEquals(RoomId("ok_small:b"), mob.roomId)
    }

    @Test
    fun `loads items from a zone file`() {
        val world = WorldLoader.loadFromResource("world/ok_small.yaml")

        val items = world.itemSpawns.associateBy { it.instance.id.value }
        assertEquals(3, items.size)

        val coin = items.getValue("ok_small:coin")
        assertEquals("coin", coin.instance.item.keyword)
        assertEquals("a silver coin", coin.instance.item.displayName)
        assertEquals(RoomId("ok_small:a"), coin.roomId)
        assertTrue(coin.mobId == null)

        val tooth = items.getValue("ok_small:tooth")
        assertEquals("tooth", tooth.instance.item.keyword)
        assertEquals(MobId("ok_small:rat"), tooth.mobId)
        assertTrue(tooth.roomId == null)

        val sigil = items.getValue("ok_small:sigil")
        assertEquals("sigil", sigil.instance.item.keyword)
        assertTrue(sigil.roomId == null)
        assertTrue(sigil.mobId == null)
    }

    @Test
    fun `fails when rooms is empty`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_empty_rooms.yaml")
            }
        assertTrue(ex.message!!.contains("no rooms", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when startRoom does not exist`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_start_missing.yaml")
            }
        assertTrue(ex.message!!.contains("startRoom", ignoreCase = true), "Got: ${ex.message}")
        assertTrue(ex.message!!.contains("does not exist", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when an exit points to a missing room`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_exit_missing_room.yaml")
            }
        assertTrue(ex.message!!.contains("points to missing room", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when a direction is invalid`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_direction.yaml")
            }
        assertTrue(ex.message!!.contains("invalid direction", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when a mob starts in a missing room`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_mob_missing_room.yaml")
            }
        assertTrue(ex.message!!.contains("starts in missing room", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when an item starts in a missing room`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_item_missing_room.yaml")
            }
        assertTrue(ex.message!!.contains("starts in missing room", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when an item starts in a missing mob`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_item_missing_mob.yaml")
            }
        assertTrue(ex.message!!.contains("starts in missing mob", ignoreCase = true), "Got: ${ex.message}")
        assertTrue(ex.message!!.contains("items.", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when an item is placed in both room and mob`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_item_dual_location.yaml")
            }
        assertTrue(ex.message!!.contains("both room and mob", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `loads item keyword override`() {
        val world = WorldLoader.loadFromResource("world/ok_item_keyword.yaml")
        val item = world.itemSpawns.single()

        assertEquals("ok_item_keyword:silver_coin", item.instance.id.value)
        assertEquals("coin", item.instance.item.keyword)
    }

    @Test
    fun `accepts direction aliases`() {
        val world = WorldLoader.loadFromResource("world/ok_small.yaml")
        val zone = "ok_small"

        val aId = RoomId("$zone:a")
        val bId = RoomId("$zone:b")

        val a = world.rooms.getValue(aId)
        val b = world.rooms.getValue(bId)

        assertEquals(bId, a.exits[Direction.NORTH])
        assertEquals(aId, b.exits[Direction.SOUTH])
    }

    class MultiZoneWorldLoaderTest {
        @Test
        fun `loads multiple zones and resolves cross-zone exits`() {
            val world =
                WorldLoader.loadFromResources(
                    listOf(
                        "world/mz_forest.yaml",
                        "world/mz_swamp.yaml",
                    ),
                )

            val forestPath = RoomId("enchanted_forest:mossy_path")
            val swampEdge = RoomId("swamp:edge")

            val room = world.rooms.getValue(forestPath)
            assertEquals(swampEdge, room.exits[Direction.EAST])

            val back = world.rooms.getValue(swampEdge)
            assertEquals(forestPath, back.exits[Direction.WEST])
        }

        @Test
        fun `fails if two zones define the same fully-qualified room id`() {
            val ex =
                assertThrows(WorldLoadException::class.java) {
                    WorldLoader.loadFromResources(listOf("world/mz_dup1.yaml", "world/mz_dup2.yaml"))
                }
            assertTrue(ex.message!!.contains("Duplicate room id"))
        }

        @Test
        fun `fails if an exit targets a missing room across all zones`() {
            val ex =
                assertThrows(WorldLoadException::class.java) {
                    WorldLoader.loadFromResources(listOf("world/mz_bad_missing_target.yaml"))
                }
            assertTrue(ex.message!!.contains("points to missing room"))
        }
    }
}
