package dev.ambon.world.load

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.load.WorldLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FreshPlaytestWorldContentTest {
    @Test
    fun `fresh playtest zones load with expected feature coverage`() {
        val world =
            WorldLoader.loadFromResources(
                listOf(
                    "world/zz_playtest_grounds.yaml",
                    "world/zz_playtest_arena.yaml",
                ),
            )

        assertEquals(RoomId("playtest_grounds:arrival_hub"), world.zoneStartRoom("playtest_grounds"))
        assertEquals(RoomId("playtest_arena:arena_entry"), world.zoneStartRoom("playtest_arena"))
        assertTrue(world.isZonePvpEnabled("playtest_arena"))

        val economy = world.rooms.getValue(RoomId("playtest_grounds:economy_district"))
        val tavern = world.rooms.getValue(RoomId("playtest_grounds:tavern_social_hall"))
        val crafting = world.rooms.getValue(RoomId("playtest_grounds:crafting_yard"))
        val lab = world.rooms.getValue(RoomId("playtest_grounds:spell_status_lab"))

        assertTrue(economy.bank)
        assertTrue(tavern.tavern)
        assertEquals("forge", crafting.station)
        assertEquals("enchanting_table", lab.station)

        assertEquals(1, world.shopDefinitions.count { it.roomId == RoomId("playtest_grounds:economy_district") })
        val trainer = world.trainerDefinitions.single { it.roomId == RoomId("playtest_grounds:arrival_hub") }
        assertEquals(5, trainer.classNames.size)

        assertEquals(2, world.gatheringNodes.size)
        assertEquals(3, world.recipes.size)
        assertEquals(1, world.dungeonTemplates.size)
        assertEquals(3, world.puzzleDefinitions.size)

        assertTrue(world.rooms.containsKey(RoomId("playtest_grounds:prize_closet")))
        assertTrue(world.rooms.containsKey(RoomId("playtest_arena:duel_dust_bowl")))
    }
}
