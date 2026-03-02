package dev.ambon.domain.world.load

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.WorldFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

class WorldLoaderTest {
    @Test
    fun `loads a valid small world and wires exits`() {
        val world = dev.ambon.test.TestWorlds.okSmall
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
        val world = dev.ambon.test.TestWorlds.okSmall

        val mob = world.mobSpawns.single()
        assertEquals("ok_small:rat", mob.id.value)
        assertEquals("a small rat", mob.name)
        assertEquals(RoomId("ok_small:b"), mob.roomId)
        assertEquals(10, mob.maxHp)
        assertEquals(1, mob.minDamage)
        assertEquals(4, mob.maxDamage)
        assertEquals(0, mob.armor)
        assertEquals(30L, mob.xpReward)
        assertEquals(1, mob.drops.size)
        assertEquals(
            "ok_small:tooth",
            mob.drops
                .single()
                .itemId.value,
        )
        assertEquals(1.0, mob.drops.single().chance)
    }

    @Test
    fun `loads zone lifespan minutes`() {
        val world = dev.ambon.test.TestWorlds.okSmall

        assertEquals(1L, world.zoneLifespansMinutes["ok_small"])
    }

    @Test
    fun `loads items from a zone file`() {
        val world = dev.ambon.test.TestWorlds.okSmall

        val items = world.itemSpawns.associateBy { it.instance.id.value }
        assertEquals(3, items.size)

        val coin = items.getValue("ok_small:coin")
        assertEquals("coin", coin.instance.item.keyword)
        assertEquals("a silver coin", coin.instance.item.displayName)
        assertEquals(RoomId("ok_small:a"), coin.roomId)

        val tooth = items.getValue("ok_small:tooth")
        assertEquals("tooth", tooth.instance.item.keyword)
        assertTrue(tooth.roomId == null)

        val sigil = items.getValue("ok_small:sigil")
        assertEquals("sigil", sigil.instance.item.keyword)
        assertTrue(sigil.roomId == null)
    }

    @Test
    fun `loads item stats and slots`() {
        val world = WorldLoader.loadFromResource("world/ok_item_stats.yaml")

        val items = world.itemSpawns.associateBy { it.instance.id.value }

        val cap = items.getValue("ok_item_stats:cap")
        assertEquals(ItemSlot.HEAD, cap.instance.item.slot)
        assertEquals(0, cap.instance.item.damage)
        assertEquals(1, cap.instance.item.armor)
        assertEquals(2, cap.instance.item.constitution)

        val sword = items.getValue("ok_item_stats:sword")
        assertEquals(ItemSlot.HAND, sword.instance.item.slot)
        assertEquals(3, sword.instance.item.damage)
        assertEquals(0, sword.instance.item.armor)
        assertEquals(1, sword.instance.item.constitution)
    }

    @Test
    fun `loads item use settings`() {
        val world = WorldLoader.loadFromResource("world/ok_item_use.yaml")
        val spawn = world.itemSpawns.single()
        val potion = spawn.instance.item
        val effect = requireNotNull(potion.onUse)

        assertTrue(potion.consumable)
        assertEquals(3, potion.charges)
        assertEquals(5, effect.healHp)
        assertEquals(25L, effect.grantXp)
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
    fun `fails when lifespan is negative`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_lifespan.yaml")
            }
        assertTrue(ex.message!!.contains("lifespan", ignoreCase = true), "Got: ${ex.message}")
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
    fun `fails when an item uses deprecated mob placement`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_item_missing_mob.yaml")
            }
        assertTrue(ex.message!!.contains("deprecated", ignoreCase = true), "Got: ${ex.message}")
        assertTrue(ex.message!!.contains("drops", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when item charges are non-positive`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_item_charges.yaml")
            }
        assertTrue(ex.message!!.contains("charges", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when item onUse block has no positive effect`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_item_on_use_empty.yaml")
            }
        assertTrue(ex.message!!.contains("onUse", ignoreCase = true), "Got: ${ex.message}")
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
        val world = dev.ambon.test.TestWorlds.okSmall
        val zone = "ok_small"

        val aId = RoomId("$zone:a")
        val bId = RoomId("$zone:b")

        val a = world.rooms.getValue(aId)
        val b = world.rooms.getValue(bId)

        assertEquals(bId, a.exits[Direction.NORTH])
        assertEquals(aId, b.exits[Direction.SOUTH])
    }

    @Test
    fun `loads mob without tier or level uses standard defaults`() {
        val world = WorldLoader.loadFromResource("world/ok_mob_stats.yaml")
        val mobs = world.mobSpawns.associateBy { it.id.value }

        val rat = mobs.getValue("ok_mob_stats:rat")
        assertEquals(10, rat.maxHp)
        assertEquals(1, rat.minDamage)
        assertEquals(4, rat.maxDamage)
        assertEquals(0, rat.armor)
        assertEquals(30L, rat.xpReward)
    }

    @Test
    fun `loads mob with tier and level applies tier formula`() {
        val world = WorldLoader.loadFromResource("world/ok_mob_stats.yaml")
        val mobs = world.mobSpawns.associateBy { it.id.value }

        // standard tier, level=3: steps=2
        // hp = 10 + 2*3 = 16
        // minDamage = 1 + 2*1 = 3
        // maxDamage = 4 + 2*1 = 6
        // armor = 0
        // xpReward = 30 + 2*10 = 50
        val bandit = mobs.getValue("ok_mob_stats:bandit")
        assertEquals(16, bandit.maxHp)
        assertEquals(3, bandit.minDamage)
        assertEquals(6, bandit.maxDamage)
        assertEquals(0, bandit.armor)
        assertEquals(50L, bandit.xpReward)
    }

    @Test
    fun `loads mob with explicit stat overrides`() {
        val world = WorldLoader.loadFromResource("world/ok_mob_stats.yaml")
        val mobs = world.mobSpawns.associateBy { it.id.value }

        val mob = mobs.getValue("ok_mob_stats:override_mob")
        assertEquals(99, mob.maxHp)
        assertEquals(5, mob.minDamage)
        assertEquals(10, mob.maxDamage)
        assertEquals(2, mob.armor)
        assertEquals(999L, mob.xpReward)
    }

    @Test
    fun `loads mob with boss tier applies boss defaults`() {
        val world = WorldLoader.loadFromResource("world/ok_mob_stats.yaml")
        val mobs = world.mobSpawns.associateBy { it.id.value }

        val boss = mobs.getValue("ok_mob_stats:boss_mob")
        assertEquals(50, boss.maxHp)
        assertEquals(3, boss.minDamage)
        assertEquals(8, boss.maxDamage)
        assertEquals(3, boss.armor)
        assertEquals(200L, boss.xpReward)
    }

    @Test
    fun `fails when mob has unknown tier`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_mob_unknown_tier.yaml")
            }
        assertTrue(ex.message!!.contains("unknown tier", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when mob level is less than 1`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_mob_bad_level.yaml")
            }
        assertTrue(ex.message!!.contains("level", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when resolved maxDamage less than minDamage`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_mob_damage_range.yaml")
            }
        assertTrue(ex.message!!.contains("maxDamage", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when mob drop chance is out of range`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_mob_drop_chance.yaml")
            }
        assertTrue(ex.message!!.contains("chance", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when mob drop references missing item`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_mob_drop_missing_item.yaml")
            }
        assertTrue(ex.message!!.contains("missing item", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `loads mob with respawnSeconds`() {
        val world = WorldLoader.loadFromResource("world/ok_mob_respawn.yaml")
        val mobs = world.mobSpawns.associateBy { it.id.value }

        val rat = mobs.getValue("ok_mob_respawn:rat")
        assertEquals(60L, rat.respawnSeconds)

        val boss = mobs.getValue("ok_mob_respawn:boss")
        assertEquals(null, boss.respawnSeconds)
    }

    @Test
    fun `fails when mob respawnSeconds is zero`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_mob_respawn_zero.yaml")
            }
        assertTrue(ex.message!!.contains("respawnSeconds", ignoreCase = true), "Got: ${ex.message}")
        assertTrue(ex.message!!.contains("> 0", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `loads tutorial glade zone with all rooms mobs and items`() {
        val world =
            WorldLoader.loadFromResources(
                listOf("world/tutorial_glade.yaml"),
                zoneFilter = setOf("tutorial_glade"),
            )

        // Verify structural correctness without pinning exact counts that break
        // whenever content is added or removed.
        assertEquals(RoomId("tutorial_glade:awakening_clearing"), world.startRoom)
        assertTrue(world.rooms.isNotEmpty(), "Expected at least one room")
        assertTrue(world.mobSpawns.isNotEmpty(), "Expected at least one mob spawn")
        assertTrue(world.itemSpawns.isNotEmpty(), "Expected at least one item spawn")
        assertEquals(30L, world.zoneLifespansMinutes["tutorial_glade"])
    }

    @Test
    fun `cross-zone exit resolves when loading multiple zones`() {
        // Uses the stable test fixture multi-zone files — not production world content.
        val world =
            WorldLoader.loadFromResources(
                listOf("world/mz_forest.yaml", "world/mz_swamp.yaml"),
            )

        val forestPath = RoomId("enchanted_forest:mossy_path")
        val swampEdge = RoomId("swamp:edge")

        assertEquals(swampEdge, world.rooms.getValue(forestPath).exits[Direction.EAST])
        assertEquals(forestPath, world.rooms.getValue(swampEdge).exits[Direction.WEST])
    }

    class MultiZoneWorldLoaderTest {
        @Test
        fun `allows split-zone files when lifespan is declared in only one file`() {
            val world =
                WorldLoader.loadFromResources(
                    listOf(
                        "world/split_zone_part1.yaml",
                        "world/split_zone_part2.yaml",
                    ),
                )

            assertEquals(30L, world.zoneLifespansMinutes["split_zone"])
            assertTrue(world.rooms.containsKey(RoomId("split_zone:a")))
            assertTrue(world.rooms.containsKey(RoomId("split_zone:b")))
        }

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

    /**
     * Regression tests for production world integrity.
     *
     * Guards against cross-zone exits in production YAML files pointing to rooms that
     * do not exist. Loads the full production zone set explicitly to avoid test-classpath
     * interference (Gradle puts src/test/resources before src/main/resources, so
     * auto-discovery would find bad_*.yaml test fixtures instead of production zones).
     */
    @Tag("integration")
    class ProductionWorldTest {
        // All zone files shipped in src/main/resources/world/. Update this list when
        // adding or removing a production zone — the test will fail at load time if a
        // cross-zone exit references a zone missing from this list.
        private val productionZones =
            listOf(
                "world/aineroia_cottage.yaml",
                "world/ambon_hub.yaml",
                "world/demo_ruins.yaml",
                "world/labyrinth.yaml",
                "world/low_training_barrens.yaml",
                "world/low_training_highlands.yaml",
                "world/low_training_marsh.yaml",
                "world/low_training_mines.yaml",
                "world/noecker_resume.yaml",
                "world/tutorial_glade.yaml",
            )

        @Test
        fun `production world loads via WorldFactory defaults`() {
            val world = WorldFactory.demoWorld(resources = productionZones)
            assertTrue(world.rooms.isNotEmpty())
            // Spot-check the cross-zone wiring that was broken in #142:
            // ambon_hub:blank_arches -> NORTH -> low_training_marsh:reedwalk_landing
            assertTrue(
                world.rooms.containsKey(RoomId("low_training_marsh:reedwalk_landing")),
                "Expected low_training_marsh:reedwalk_landing to be loaded",
            )
            assertTrue(
                world.rooms.containsKey(RoomId("ambon_hub:blank_arches")),
                "Expected ambon_hub:blank_arches to be loaded",
            )
        }

        @Test
        fun `application yaml world resources load without cross-zone errors`() {
            // world.resources is now empty in application.yaml (auto-discovery is used
            // at runtime). This test validates the full production zone set loads cleanly
            // and that world.startRoom resolves correctly.
            val text =
                WorldLoader::class.java.classLoader
                    .getResource("application.yaml")!!
                    .readText()
            val root = ObjectMapper(YAMLFactory()).readTree(text)
            val startRoom =
                root
                    .path("ambonMUD")
                    .path("world")
                    .path("startRoom")
                    .textValue()
                    ?.let { RoomId(it) }
            val world =
                WorldFactory.demoWorld(
                    resources = productionZones,
                    startRoom = startRoom,
                )
            assertTrue(world.rooms.isNotEmpty())
            assertEquals(RoomId("ambon_hub:hall_of_portals"), world.startRoom)
        }
    }

    class ZoneFilteredWorldLoaderTest {
        private val multiZonePaths = listOf("world/mz_forest.yaml", "world/mz_swamp.yaml")

        @Test
        fun `empty zone filter loads all zones`() {
            val world = WorldLoader.loadFromResources(multiZonePaths, zoneFilter = emptySet())

            assertTrue(world.rooms.containsKey(RoomId("enchanted_forest:trailhead")))
            assertTrue(world.rooms.containsKey(RoomId("swamp:edge")))
        }

        @Test
        fun `zone filter loads only matching zones`() {
            val world = WorldLoader.loadFromResources(multiZonePaths, zoneFilter = setOf("enchanted_forest"))

            assertTrue(world.rooms.containsKey(RoomId("enchanted_forest:trailhead")))
            assertTrue(world.rooms.containsKey(RoomId("enchanted_forest:mossy_path")))
            assertTrue(!world.rooms.containsKey(RoomId("swamp:edge")))
            assertTrue(!world.rooms.containsKey(RoomId("swamp:deep")))
        }

        @Test
        fun `cross-zone exits are preserved but targets are not validated`() {
            val world = WorldLoader.loadFromResources(multiZonePaths, zoneFilter = setOf("enchanted_forest"))

            val mossyPath = world.rooms.getValue(RoomId("enchanted_forest:mossy_path"))
            // Cross-zone exit to swamp:edge is preserved even though swamp is not loaded
            assertEquals(RoomId("swamp:edge"), mossyPath.exits[Direction.EAST])
        }

        @Test
        fun `startRoom comes from first filtered zone`() {
            val world = WorldLoader.loadFromResources(multiZonePaths, zoneFilter = setOf("swamp"))

            assertEquals(RoomId("swamp:edge"), world.startRoom)
        }

        @Test
        fun `zone filter with no matching zones throws`() {
            val ex =
                assertThrows(WorldLoadException::class.java) {
                    WorldLoader.loadFromResources(multiZonePaths, zoneFilter = setOf("nonexistent"))
                }
            assertTrue(ex.message!!.contains("No zone files match"), "Got: ${ex.message}")
        }

        @Test
        fun `filtering one zone from multi-zone world produces valid world`() {
            val world = WorldLoader.loadFromResources(multiZonePaths, zoneFilter = setOf("swamp"))

            assertEquals(2, world.rooms.size)
            assertTrue(world.rooms.containsKey(RoomId("swamp:edge")))
            assertTrue(world.rooms.containsKey(RoomId("swamp:deep")))

            // Cross-zone exit back to forest is preserved
            val edge = world.rooms.getValue(RoomId("swamp:edge"))
            assertEquals(RoomId("enchanted_forest:mossy_path"), edge.exits[Direction.WEST])
        }
    }

    class ShopAndGoldWorldLoaderTest {
        @Test
        fun `loads shop definitions from zone file`() {
            val world = WorldLoader.loadFromResource("world/ok_shop.yaml")

            assertEquals(1, world.shopDefinitions.size)
            val shop = world.shopDefinitions.single()
            assertEquals("Market Vendor", shop.name)
            assertEquals(RoomId("ok_shop:market"), shop.roomId)
            assertEquals(2, shop.itemIds.size)
        }

        @Test
        fun `loads item basePrice`() {
            val world = WorldLoader.loadFromResource("world/ok_shop.yaml")
            val items = world.itemSpawns.associateBy { it.instance.id.value }

            val sword = items.getValue("ok_shop:sword")
            assertEquals(50, sword.instance.item.basePrice)

            val trophy = items.getValue("ok_shop:trophy")
            assertEquals(0, trophy.instance.item.basePrice)
        }

        @Test
        fun `loads mob gold range from tier defaults`() {
            val world = WorldLoader.loadFromResource("world/ok_mob_stats.yaml")
            val mobs = world.mobSpawns.associateBy { it.id.value }

            // Standard tier level 1: goldMin=2, goldMax=8
            val rat = mobs.getValue("ok_mob_stats:rat")
            assertEquals(2L, rat.goldMin)
            assertEquals(8L, rat.goldMax)

            // Standard tier level 3 (steps=2): goldMin=2+2*2=6, goldMax=8+2*2=12
            val bandit = mobs.getValue("ok_mob_stats:bandit")
            assertEquals(6L, bandit.goldMin)
            assertEquals(12L, bandit.goldMax)

            // Boss tier level 1: goldMin=50, goldMax=100
            val boss = mobs.getValue("ok_mob_stats:boss_mob")
            assertEquals(50L, boss.goldMin)
            assertEquals(100L, boss.goldMax)
        }

        @Test
        fun `fails when shop references missing room`() {
            val ex =
                assertThrows(WorldLoadException::class.java) {
                    WorldLoader.loadFromResource("world/bad_shop_missing_room.yaml")
                }
            assertTrue(ex.message!!.contains("room", ignoreCase = true), "Got: ${ex.message}")
        }

        @Test
        fun `fails when shop references missing item`() {
            val ex =
                assertThrows(WorldLoadException::class.java) {
                    WorldLoader.loadFromResource("world/bad_shop_missing_item.yaml")
                }
            assertTrue(ex.message!!.contains("item", ignoreCase = true), "Got: ${ex.message}")
        }
    }

    @Test
    fun `loads mob with dialogue tree`() {
        val world = WorldLoader.loadFromResource("world/ok_dialogue.yaml")
        val mob = world.mobSpawns.single()
        val dialogue = mob.dialogue
        assertTrue(dialogue != null, "Dialogue should be loaded")
        assertEquals("root", dialogue!!.rootNodeId)
        assertEquals(5, dialogue.nodes.size)
        val rootNode = dialogue.nodes["root"]!!
        assertEquals(4, rootNode.choices.size)
        assertEquals("about", rootNode.choices[0].nextNodeId)
        assertEquals(3, rootNode.choices[1].minLevel)
        assertEquals("WARRIOR", rootNode.choices[2].requiredClass)
        assertTrue(rootNode.choices[3].nextNodeId == null)
    }

    @Test
    fun `fails when dialogue is missing root node`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_dialogue_missing_root.yaml")
            }
        assertTrue(ex.message!!.contains("root", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `fails when dialogue has broken node reference`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_dialogue_broken_ref.yaml")
            }
        assertTrue(
            ex.message!!.contains("nonexistent_node", ignoreCase = true),
            "Got: ${ex.message}",
        )
    }

    @Test
    fun `startRoomOverride takes precedence over first-file start room`() {
        // mz_forest loads first; its startRoom (enchanted_forest:trailhead) would normally win.
        // The override points into mz_swamp (second file) instead.
        val world =
            WorldLoader.loadFromResources(
                paths = listOf("world/mz_forest.yaml", "world/mz_swamp.yaml"),
                startRoomOverride = RoomId("swamp:edge"),
            )
        assertEquals(RoomId("swamp:edge"), world.startRoom)
    }

    @Test
    fun `startRoomOverride fails when the room does not exist in the merged world`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResources(
                    paths = listOf("world/ok_small.yaml"),
                    startRoomOverride = RoomId("ok_small:nonexistent"),
                )
            }
        assertTrue(ex.message!!.contains("nonexistent", ignoreCase = true), "Got: ${ex.message}")
    }

    @Test
    fun `WorldFactory loads from explicit resource list`() {
        val world = WorldFactory.demoWorld(resources = listOf("world/mz_forest.yaml", "world/mz_swamp.yaml"))
        assertTrue(world.rooms.isNotEmpty())
    }
}
