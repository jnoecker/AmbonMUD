package dev.ambon.engine.dungeon

import dev.ambon.domain.dungeon.DungeonMobPoolDef
import dev.ambon.domain.dungeon.DungeonRoomTemplateDef
import dev.ambon.domain.dungeon.DungeonRoomType
import dev.ambon.domain.dungeon.DungeonTemplateDef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class DungeonGeneratorTest {
    private val template = DungeonTemplateDef(
        id = "test:crypt",
        name = "Test Crypt",
        roomCountMin = 10,
        roomCountMax = 15,
        roomTemplates = mapOf(
            DungeonRoomType.ENTRANCE to listOf(
                DungeonRoomTemplateDef("Crypt Entrance", "You enter the crypt."),
            ),
            DungeonRoomType.CORRIDOR to listOf(
                DungeonRoomTemplateDef("Dark Corridor", "A dark corridor stretches ahead."),
                DungeonRoomTemplateDef("Damp Passage", "Water drips from the ceiling."),
            ),
            DungeonRoomType.CHAMBER to listOf(
                DungeonRoomTemplateDef("Burial Chamber", "Sarcophagi line the walls."),
            ),
            DungeonRoomType.TREASURE to listOf(
                DungeonRoomTemplateDef("Hidden Vault", "Gold glints in the torchlight."),
            ),
            DungeonRoomType.BOSS to listOf(
                DungeonRoomTemplateDef("The Crypt Heart", "A vast chamber with an altar."),
            ),
        ),
        mobPools = DungeonMobPoolDef(
            common = listOf("skeleton", "spider"),
            elite = listOf("guardian"),
            boss = listOf("crypt_lord"),
        ),
    )

    private val generator = DungeonGenerator(Random(42))

    @Test
    fun `generates layout with correct room count range`() {
        val layout = generator.generate(template)
        assertTrue(layout.rooms.size in 10..15, "Room count ${layout.rooms.size} should be in 10..15")
    }

    @Test
    fun `first room is entrance`() {
        val layout = generator.generate(template)
        assertEquals(DungeonRoomType.ENTRANCE, layout.rooms[layout.entranceIndex].type)
    }

    @Test
    fun `last indexed room is boss`() {
        val layout = generator.generate(template)
        assertEquals(DungeonRoomType.BOSS, layout.rooms[layout.bossIndex].type)
    }

    @Test
    fun `all rooms are connected via exits`() {
        val layout = generator.generate(template)
        // BFS from entrance should reach all rooms
        val visited = mutableSetOf<Int>()
        val queue = ArrayDeque<Int>()
        queue.add(layout.entranceIndex)
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            if (idx in visited) continue
            visited.add(idx)
            for ((_, target) in layout.rooms[idx].exits) {
                if (target !in visited) queue.add(target)
            }
        }
        assertEquals(layout.rooms.size, visited.size, "All rooms should be reachable from entrance")
    }

    @Test
    fun `exits are bidirectional`() {
        val layout = generator.generate(template)
        for (room in layout.rooms) {
            for ((dir, target) in room.exits) {
                val targetRoom = layout.rooms[target]
                assertTrue(
                    targetRoom.exits.containsValue(room.index),
                    "Room ${room.index} → $dir → $target should have a return exit",
                )
            }
        }
    }

    @Test
    fun `entrance has no mobs`() {
        val layout = generator.generate(template)
        assertTrue(layout.rooms[layout.entranceIndex].mobIds.isEmpty())
    }

    @Test
    fun `boss room has a boss mob`() {
        val layout = generator.generate(template)
        val bossRoom = layout.rooms[layout.bossIndex]
        assertTrue(bossRoom.mobIds.contains("crypt_lord"))
    }

    @Test
    fun `corridors and chambers have common mobs`() {
        val layout = generator.generate(template)
        val combatRooms = layout.rooms.filter {
            it.type == DungeonRoomType.CORRIDOR || it.type == DungeonRoomType.CHAMBER
        }
        assertTrue(combatRooms.any { it.mobIds.isNotEmpty() })
    }

    @Test
    fun `deterministic with same seed`() {
        val gen1 = DungeonGenerator(Random(123))
        val gen2 = DungeonGenerator(Random(123))
        val layout1 = gen1.generate(template)
        val layout2 = gen2.generate(template)
        assertEquals(layout1.rooms.size, layout2.rooms.size)
        assertEquals(layout1.bossIndex, layout2.bossIndex)
        for (i in layout1.rooms.indices) {
            assertEquals(layout1.rooms[i].type, layout2.rooms[i].type)
            assertEquals(layout1.rooms[i].title, layout2.rooms[i].title)
        }
    }

    @Test
    fun `different seeds produce different layouts`() {
        val gen1 = DungeonGenerator(Random(1))
        val gen2 = DungeonGenerator(Random(999))
        val layout1 = gen1.generate(template)
        val layout2 = gen2.generate(template)
        // At least room count or boss index should differ
        val differ = layout1.rooms.size != layout2.rooms.size ||
            layout1.bossIndex != layout2.bossIndex ||
            layout1.rooms.map { it.title } != layout2.rooms.map { it.title }
        assertTrue(differ, "Different seeds should produce different layouts")
    }

    @Test
    fun `larger room count range is respected`() {
        val bigTemplate = template.copy(roomCountMin = 20, roomCountMax = 25)
        val gen = DungeonGenerator(Random(42))
        val layout = gen.generate(bigTemplate)
        assertTrue(layout.rooms.size in 20..25, "Room count ${layout.rooms.size} should be in 20..25")
    }
}
