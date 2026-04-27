package dev.ambon.domain.world.load

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.items.ItemType
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.WorldFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `assigns minimap coordinates via BFS from start room`() {
        val world = dev.ambon.test.TestWorlds.okSmall

        val a = world.rooms.getValue(RoomId("ok_small:a"))
        val b = world.rooms.getValue(RoomId("ok_small:b"))

        // Start room "a" should be at origin
        assertEquals(0, a.mapX, "start room mapX")
        assertEquals(0, a.mapY, "start room mapY")

        // "b" is north of "a", so mapY should be -1
        assertEquals(0, b.mapX, "north room mapX")
        assertEquals(-1, b.mapY, "north room mapY")
    }

    @Test
    fun `assigns minimap coordinates for multi-room zone`() {
        val world = dev.ambon.test.TestWorlds.testWorld
        val rooms = world.rooms

        // The test_world has a hub with rooms in multiple directions.
        // Verify no two rooms in the same zone share coordinates.
        val coordsByZone = rooms.values.groupBy { it.id.zone }
        for ((zone, zoneRooms) in coordsByZone) {
            val seen = mutableSetOf<Pair<Int, Int>>()
            for (room in zoneRooms) {
                val coord = room.mapX to room.mapY
                assertTrue(
                    seen.add(coord),
                    "Zone '$zone' has coordinate collision at (${ room.mapX }, ${ room.mapY }) " +
                        "for room '${room.id.value}' — another room already occupies this position",
                )
            }
        }
    }

    @Test
    fun `loads mobs from a zone file`() {
        val world = dev.ambon.test.TestWorlds.okSmall

        val spawn = world.mobSpawns.single()
        val mob = world.mobTemplate(spawn.templateId)!!
        assertEquals("ok_small:rat", mob.id.value)
        assertEquals("a small rat", mob.name)
        assertEquals(RoomId("ok_small:b"), spawn.roomId)
        assertEquals(10, mob.maxHp)
        assertEquals(1, mob.damage.min)
        assertEquals(4, mob.damage.max)
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
    fun `legacy room shorthand produces a single placement with template id`() {
        val world = dev.ambon.test.TestWorlds.okSmall
        val rat = world.mobTemplates.values.single { it.id.value == "ok_small:rat" }
        val placements = world.mobSpawns.filter { it.templateId == rat.id }

        assertEquals(1, placements.size)
        // Single global instance — placement id matches the template id (no suffix).
        assertEquals(rat.id, placements[0].id)
        assertEquals(0, placements[0].instanceIndex)
        assertEquals(RoomId("ok_small:b"), placements[0].roomId)
    }

    @Test
    fun `spawns list with multiple entries produces one placement per copy`() {
        val world = WorldLoader.loadFromResource("world/ok_mob_multi_spawn.yaml")
        val placementsByTemplate =
            world.mobSpawns.groupBy { it.templateId.value }

        // Single-spawn unique NPC keeps a bare id.
        val unique = placementsByTemplate.getValue("ok_multi:unique_npc")
        assertEquals(1, unique.size)
        assertEquals("ok_multi:unique_npc", unique[0].id.value)
        assertEquals(0, unique[0].instanceIndex)

        // Two rooms, one each → two placements with stable #0/#1 ids.
        val twoRooms = placementsByTemplate.getValue("ok_multi:trash_two_rooms")
        assertEquals(2, twoRooms.size)
        assertEquals(listOf("ok_multi:trash_two_rooms#0", "ok_multi:trash_two_rooms#1"), twoRooms.map { it.id.value })
        assertEquals(setOf(RoomId("ok_multi:a"), RoomId("ok_multi:b")), twoRooms.map { it.roomId }.toSet())

        // count: 3 → three placements in the same room.
        val ratCount = placementsByTemplate.getValue("ok_multi:trash_count")
        assertEquals(3, ratCount.size)
        assertEquals(listOf(0, 1, 2), ratCount.map { it.instanceIndex })
        assertTrue(ratCount.all { it.roomId == RoomId("ok_multi:b") })

        // Mixed: count 2 in room a, then 1 in room b → three placements indexed 0,1,2.
        val slimes = placementsByTemplate.getValue("ok_multi:trash_mixed")
        assertEquals(3, slimes.size)
        assertEquals(listOf(0, 1, 2), slimes.map { it.instanceIndex })
        assertEquals(
            listOf(RoomId("ok_multi:a"), RoomId("ok_multi:a"), RoomId("ok_multi:b")),
            slimes.map { it.roomId },
        )
    }

    @Test
    fun `template fields are reachable from every placement of the same template`() {
        val world = WorldLoader.loadFromResource("world/ok_mob_multi_spawn.yaml")
        val ratPlacements = world.mobSpawns.filter { it.templateId.value == "ok_multi:trash_count" }
        val templates = ratPlacements.map { world.mobTemplate(it.templateId)!! }
        // All three placements resolve to the same template instance — name/stats are shared.
        assertTrue(templates.all { it.name == "a rat" })
        assertEquals(1, templates.distinctBy { it.id }.size)
    }

    @Test
    fun `fails when mob declares both room and spawns`() {
        val ex = assertThrows(WorldLoadException::class.java) {
            WorldLoader.loadFromResource("world/bad_mob_room_and_spawns.yaml")
        }
        assertTrue(ex.message!!.contains("both 'room' and 'spawns'"), "Got: ${ex.message}")
    }

    @Test
    fun `fails when spawn entry has count zero`() {
        val ex = assertThrows(WorldLoadException::class.java) {
            WorldLoader.loadFromResource("world/bad_mob_spawn_count_zero.yaml")
        }
        assertTrue(ex.message!!.contains("count"), "Got: ${ex.message}")
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
        assertEquals(2, cap.instance.item.stats["CON"])

        val sword = items.getValue("ok_item_stats:sword")
        assertEquals(ItemSlot.WEAPON, sword.instance.item.slot)
        assertEquals(3, sword.instance.item.damage)
        assertEquals(0, sword.instance.item.armor)
        assertEquals(1, sword.instance.item.stats["CON"])
    }

    @Test
    fun `loads declared item types and quest-item flag`() {
        val world = WorldLoader.loadFromResource("world/ok_item_types.yaml")
        val items = world.itemSpawns.associateBy { it.instance.id.value }

        val relic = items.getValue("ok_item_types:relic").instance.item
        assertEquals(ItemType.QUEST, relic.itemType)
        assertTrue(relic.questItem)
        assertEquals(ItemType.QUEST, relic.resolvedType())

        // Inferred: has basePrice, no slot/consumable -> TREASURE
        val trinket = items.getValue("ok_item_types:trinket").instance.item
        assertNull(trinket.itemType)
        assertFalse(trinket.questItem)
        assertEquals(ItemType.TREASURE, trinket.resolvedType())

        // Inferred: nothing special -> MISC
        val plain = items.getValue("ok_item_types:plain").instance.item
        assertEquals(ItemType.MISC, plain.resolvedType())

        // Inferred from slot -> EQUIPMENT
        val blade = items.getValue("ok_item_types:blade").instance.item
        assertEquals(ItemType.EQUIPMENT, blade.resolvedType())

        // Inferred from consumable flag -> CONSUMABLE
        val elixir = items.getValue("ok_item_types:elixir").instance.item
        assertEquals(ItemType.CONSUMABLE, elixir.resolvedType())

        // questItem=true always resolves to QUEST regardless of slot
        val signet = items.getValue("ok_item_types:marked_gear").instance.item
        assertTrue(signet.questItem)
        assertEquals(ItemType.QUEST, signet.resolvedType())
    }

    @Test
    fun `rejects unknown itemType value`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_item_type.yaml")
            }
        assertTrue(
            ex.message!!.contains("itemType", ignoreCase = true),
            "Got: ${ex.message}",
        )
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
    fun `exit to missing room loads but is marked remote`() {
        val world = WorldLoader.loadFromResource("world/bad_exit_missing_room.yaml")
        val roomA = world.rooms.getValue(RoomId("bad_exit_missing_room:a"))
        assertTrue(roomA.exits.containsKey(Direction.NORTH), "Exit should still exist")
        assertTrue(roomA.remoteExits.contains(Direction.NORTH), "Exit to missing room should be marked remote")
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
        val mobs = world.mobTemplates.mapKeys { it.key.value }

        val rat = mobs.getValue("ok_mob_stats:rat")
        assertEquals(10, rat.maxHp)
        assertEquals(1, rat.damage.min)
        assertEquals(4, rat.damage.max)
        assertEquals(0, rat.armor)
        assertEquals(30L, rat.xpReward)
    }

    @Test
    fun `loads mob with tier and level applies tier formula`() {
        val world = WorldLoader.loadFromResource("world/ok_mob_stats.yaml")
        val mobs = world.mobTemplates.mapKeys { it.key.value }

        // standard tier, level=3: steps=2
        // hp = 10 + 2*3 = 16
        // minDamage = 1 + 2*1 = 3
        // maxDamage = 4 + 2*1 = 6
        // armor = 0
        // xpReward = 30 + 2*10 = 50
        val bandit = mobs.getValue("ok_mob_stats:bandit")
        assertEquals(16, bandit.maxHp)
        assertEquals(3, bandit.damage.min)
        assertEquals(6, bandit.damage.max)
        assertEquals(0, bandit.armor)
        assertEquals(50L, bandit.xpReward)
    }

    @Test
    fun `loads mob with explicit stat overrides`() {
        val world = WorldLoader.loadFromResource("world/ok_mob_stats.yaml")
        val mobs = world.mobTemplates.mapKeys { it.key.value }

        val mob = mobs.getValue("ok_mob_stats:override_mob")
        assertEquals(99, mob.maxHp)
        assertEquals(5, mob.damage.min)
        assertEquals(10, mob.damage.max)
        assertEquals(2, mob.armor)
        assertEquals(999L, mob.xpReward)
    }

    @Test
    fun `loads mob with boss tier applies boss defaults`() {
        val world = WorldLoader.loadFromResource("world/ok_mob_stats.yaml")
        val mobs = world.mobTemplates.mapKeys { it.key.value }

        val boss = mobs.getValue("ok_mob_stats:boss_mob")
        assertEquals(50, boss.maxHp)
        assertEquals(3, boss.damage.min)
        assertEquals(8, boss.damage.max)
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
        val mobs = world.mobTemplates.mapKeys { it.key.value }

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
        fun `exit to missing room across zones loads but is marked remote`() {
            val world = WorldLoader.loadFromResources(listOf("world/mz_bad_missing_target.yaml"))
            val mossyPath = world.rooms.getValue(RoomId("enchanted_forest:mossy_path"))
            assertTrue(mossyPath.exits.containsKey(Direction.EAST), "Exit should still exist")
            assertTrue(mossyPath.remoteExits.contains(Direction.EAST), "Exit to missing room should be marked remote")
        }
    }

    /**
     * Regression tests for production world integrity.
     *
     * Guards against cross-zone exits in production YAML files pointing to rooms that
     * do not exist. Scans src/main/resources/world/ directly from the filesystem so
     * that new zones are picked up automatically and test-classpath interference
     * (bad_*.yaml fixtures) is avoided entirely.
     */
    @Tag("integration")
    class ProductionWorldTest {
        // Scan the source tree directly so new zones are included automatically without
        // any change to this test. Gradle runs tests from the project root, so the
        // relative path is stable. Non-zone YAMLs (e.g. achievements.yaml) are filtered
        // out by the zone: key check, matching the same logic used by WorldFactory at runtime.
        private val productionZones: List<String> by lazy {
            val worldDir = java.io.File("src/main/resources/world")
            check(worldDir.isDirectory) {
                "Could not find src/main/resources/world — is the working directory the project root?"
            }
            worldDir
                .listFiles { f -> f.extension == "yaml" }!!
                .filter { f -> f.useLines { lines -> lines.take(20).any { it.trimStart().startsWith("zone:") } } }
                .map { "world/${it.name}" }
                .sorted()
        }

        @Test
        fun `production world loads via WorldFactory defaults`() {
            val world = WorldFactory.demoWorld(resources = productionZones)
            assertTrue(world.rooms.isNotEmpty())
            // Spot-check the start zone loads:
            assertTrue(
                world.rooms.containsKey(RoomId("academy:academy_gates")),
                "Expected academy:academy_gates to be loaded",
            )
        }

        @Test
        fun `production world has no coordinate collisions within any zone`() {
            val world = WorldFactory.demoWorld(resources = productionZones)
            val coordsByZone = world.rooms.values.groupBy { it.id.zone }
            for ((zone, zoneRooms) in coordsByZone) {
                val seen = mutableMapOf<Pair<Int, Int>, String>()
                for (room in zoneRooms) {
                    val coord = room.mapX to room.mapY
                    val existing = seen.put(coord, room.id.value)
                    assertNull(
                        existing,
                    ) {
                        "Zone '$zone' coordinate collision at (${room.mapX}, ${room.mapY}): " +
                            "'${room.id.value}' collides with '$existing'"
                    }
                }
            }
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
                    .path("ambonmud")
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
            assertEquals(RoomId("academy:academy_gates"), world.startRoom)
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
            val mobs = world.mobTemplates.mapKeys { it.key.value }

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
    fun `zone faction propagates to mobs that lack their own`() {
        val world = WorldLoader.loadFromResource("world/ok_faction_gates.yaml")
        val mobs = world.mobTemplates.mapKeys { it.key.value }
        assertEquals("royal_court", mobs.getValue("ok_faction_gates:inheritor").faction)
        assertEquals("rebel_cell", mobs.getValue("ok_faction_gates:dissenter").faction)
    }

    @Test
    fun `loads shop and quest reputation requirements`() {
        val world = WorldLoader.loadFromResource("world/ok_faction_gates.yaml")
        val shop = world.shopDefinitions.single { it.id == "ok_faction_gates:court_armorer" }
        assertEquals("royal_court", shop.requiredReputation?.faction)
        assertEquals(250, shop.requiredReputation?.min)
        assertNull(shop.requiredReputation?.max)

        val quest = world.questDefinitions.single { it.id == "ok_faction_gates:rebel_mission" }
        assertEquals("rebel_cell", quest.requiredReputation?.faction)
        assertEquals(-500, quest.requiredReputation?.max)
        assertNull(quest.requiredReputation?.min)
    }

    @Test
    fun `shop requiredReputation with unknown faction is rejected`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource(
                    "world/bad_shop_rep_unknown_faction.yaml",
                    factionIds = setOf("royal_court"),
                )
            }
        assertTrue(ex.message!!.contains("no_such_faction"), "Got: ${ex.message}")
    }

    @Test
    fun `quest requiredReputation with min greater than max is rejected`() {
        val ex =
            assertThrows(WorldLoadException::class.java) {
                WorldLoader.loadFromResource("world/bad_quest_rep_invalid_range.yaml")
            }
        assertTrue(ex.message!!.contains("min"), "Got: ${ex.message}")
        assertTrue(ex.message!!.contains("max"), "Got: ${ex.message}")
    }

    @Test
    fun `loads mob with dialogue tree`() {
        val world = WorldLoader.loadFromResource("world/ok_dialogue.yaml")
        val mob = world.mobTemplates.values.single()
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
    fun `image paths are prefixed with images`() {
        val world = WorldLoader.loadFromResource("world/ok_images.yaml")

        val room = world.rooms.getValue(RoomId("ok_images:clearing"))
        assertEquals("/images/forest/clearing.png", room.image)

        val cave = world.rooms.getValue(RoomId("ok_images:cave"))
        assertNull(cave.image)

        val mob = world.mobTemplates.values.single()
        assertEquals("/images/mobs/wolf.png", mob.image)

        val items = world.itemSpawns.associateBy { it.instance.id.value }
        assertEquals("/images/items/gem.png", items.getValue("ok_images:gem").instance.item.image)

        val node = world.gatheringNodes.single()
        assertEquals("/images/nodes/crystal.png", node.image)
    }

    @Test
    fun `zone image defaults apply when entity has no image`() {
        val world = WorldLoader.loadFromResource("world/ok_image_defaults.yaml")

        // Room without explicit image gets zone default
        val plaza = world.rooms.getValue(RoomId("ok_image_defaults:plaza"))
        assertEquals("/images/defaults/room.png", plaza.image)

        // Room with explicit image keeps its own
        val alley = world.rooms.getValue(RoomId("ok_image_defaults:alley"))
        assertEquals("/images/alley/custom.png", alley.image)

        // Mob without explicit image gets zone default
        val mobs = world.mobTemplates.mapKeys { it.key.value }
        assertEquals("/images/defaults/mob.png", mobs.getValue("ok_image_defaults:guard").image)

        // Mob with explicit image keeps its own
        assertEquals("/images/mobs/thief.png", mobs.getValue("ok_image_defaults:thief").image)

        // Item without explicit image gets zone default
        val items = world.itemSpawns.associateBy { it.instance.id.value }
        assertEquals("/images/defaults/item.png", items.getValue("ok_image_defaults:bread").instance.item.image)

        // Item with explicit image keeps its own
        assertEquals("/images/items/dagger.png", items.getValue("ok_image_defaults:dagger").instance.item.image)
    }

    @Test
    fun `WorldFactory loads from explicit resource list`() {
        val world = WorldFactory.demoWorld(resources = listOf("world/mz_forest.yaml", "world/mz_swamp.yaml"))
        assertTrue(world.rooms.isNotEmpty())
    }

    @Test
    fun `pvpEnabled zone is parsed and exposed via isZonePvpEnabled`() {
        val world = WorldLoader.loadFromResource("world/ok_pvp_zone.yaml")
        assertTrue(world.isZonePvpEnabled("pvp_zone"), "Expected pvp_zone to have PvP enabled")

        val startRoom = world.zoneStartRoom("pvp_zone")
        assertEquals(RoomId("pvp_zone:pvp_start"), startRoom)
    }

    @Test
    fun `non-pvp zone returns false for isZonePvpEnabled`() {
        val world = dev.ambon.test.TestWorlds.okSmall
        assertFalse(world.isZonePvpEnabled("ok_small"), "Expected ok_small to NOT have PvP enabled")
    }

    @Test
    fun `loads puzzles from a zone file`() {
        val world = dev.ambon.test.TestWorlds.okPuzzles
        val puzzles = world.puzzleDefinitions
        assertTrue(puzzles.isNotEmpty(), "Expected at least one puzzle")

        val riddle = puzzles.first { it.id == "ok_puzzles:sphinx_riddle" }
        assertEquals(dev.ambon.domain.puzzle.PuzzleType.RIDDLE, riddle.type)
        assertEquals(RoomId("ok_puzzles:entrance"), riddle.roomId)
        assertEquals("ok_puzzles:sphinx", riddle.mobId)
        assertTrue(riddle.acceptableAnswers.contains("mountain"))

        val sequence = puzzles.first { it.id == "ok_puzzles:lever_sequence" }
        assertEquals(dev.ambon.domain.puzzle.PuzzleType.SEQUENCE, sequence.type)
        assertEquals(3, sequence.steps.size)
    }

    @Test
    fun `puzzle with unlock_exit reward parses direction and target`() {
        val world = dev.ambon.test.TestWorlds.okPuzzles
        val riddle = world.puzzleDefinitions.first { it.id == "ok_puzzles:sphinx_riddle" }
        val reward = riddle.reward as dev.ambon.domain.puzzle.PuzzleReward.UnlockExit
        assertEquals(Direction.NORTH, reward.direction)
        assertEquals(RoomId("ok_puzzles:hidden_room"), reward.targetRoom)
    }
}
