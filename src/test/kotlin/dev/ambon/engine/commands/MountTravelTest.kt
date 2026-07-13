package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.MountTravelConfig
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemType
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.sprite.SpriteCategory
import dev.ambon.domain.sprite.SpriteDefinition
import dev.ambon.domain.sprite.SpriteRequirement
import dev.ambon.domain.sprite.SpriteVariant
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.domain.world.ZoneWorldMap
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.MountTravelSystem
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.SpriteRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.scheduler.Scheduler
import dev.ambon.test.MutableClock
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MountTravelTest {
    @Nested
    inner class Parser {
        @Test
        fun `travel with a room id parses to Travel`() {
            val result = CommandParser.parse("travel plains:gate")
            assertTrue(result is Command.Travel)
            assertEquals("plains:gate", (result as Command.Travel).destination)
        }

        @Test
        fun `travel without arg returns Invalid`() {
            assertTrue(CommandParser.parse("travel") is Command.Invalid)
        }

        @Test
        fun `trade and train still parse independently of travel`() {
            assertTrue(CommandParser.parse("travel x:y") is Command.Travel)
            assertTrue(CommandParser.parse("train") !is Command.Travel)
        }
    }

    @Nested
    inner class Rides {
        @Test
        fun `refuses without a mount`() = runTest {
            val env = env()
            env.explored(listOf(stable, road1, road2, gate))
            env.system.requestTravel(env.sid, gate.value)
            env.assertError("mount")
            assertEquals(stable, env.me().roomId)
        }

        @Test
        fun `refuses an unexplored destination`() = runTest {
            val env = env()
            env.ownMount()
            env.explored(listOf(stable, road1))
            env.system.requestTravel(env.sid, gate.value)
            env.assertError("explored")
        }

        @Test
        fun `refuses when the only route crosses unexplored rooms`() = runTest {
            val env = env()
            env.ownMount()
            // Destination explored, but the middle of the only route is not — only
            // wings could reach it, and the pony has none.
            env.explored(listOf(stable, road1, gate))
            env.system.requestTravel(env.sid, gate.value)
            env.assertError("flying")
        }

        @Test
        fun `refuses while in combat`() = runTest {
            val env = env()
            env.ownMount()
            env.explored(listOf(stable, road1, road2, gate))
            env.mobs.upsert(MobState(MobId("plains:wolf"), "a wolf", stable, hp = 10, maxHp = 10))
            assertNull(env.combat.startCombat(env.sid, "wolf"))
            env.outbound.drainAll()
            env.system.requestTravel(env.sid, gate.value)
            env.assertError("battle")
        }

        @Test
        fun `rides the path one room per interval and dismounts on arrival`() = runTest {
            val env = env()
            env.ownMount()
            env.explored(listOf(stable, road1, road2, gate))

            env.system.requestTravel(env.sid, gate.value)
            assertEquals("dappled_pony", env.me().ridingMountId, "mount shows while riding")
            assertEquals(stable, env.me().roomId, "departure is not instant")

            env.tick() // step 1
            assertEquals(road1, env.me().roomId)
            assertTrue(env.system.isRiding(env.sid))

            env.tick() // step 2
            assertEquals(road2, env.me().roomId)

            env.tick() // arrival
            assertEquals(gate, env.me().roomId)
            assertNull(env.me().ridingMountId, "dismounted on arrival")
            assertTrue(!env.system.isRiding(env.sid))
            val texts = env.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(texts.any { it.contains("arrive", ignoreCase = true) }, "got=$texts")
        }

        @Test
        fun `destination may sit one hop into an adjacent zone`() = runTest {
            val env = env()
            env.ownMount()
            env.explored(listOf(stable, road1, road2, gate, border))

            env.system.requestTravel(env.sid, border.value)
            repeat(4) { env.tick() }
            assertEquals(border, env.me().roomId)
        }

        @Test
        fun `rooms deeper in an adjacent zone need a flying mount`() = runTest {
            val env = env()
            env.ownMount()
            env.explored(listOf(stable, road1, road2, gate, border, deep))

            env.system.requestTravel(env.sid, deep.value)
            env.assertError("flying")
        }

        @Test
        fun `combat starting mid-ride cancels and dismounts in place`() = runTest {
            val env = env()
            env.ownMount()
            env.explored(listOf(stable, road1, road2, gate))

            env.system.requestTravel(env.sid, gate.value)
            env.tick()
            assertEquals(road1, env.me().roomId)

            env.mobs.upsert(MobState(MobId("plains:bandit"), "a bandit", road1, hp = 10, maxHp = 10))
            assertNull(env.combat.startCombat(env.sid, "bandit"))
            env.outbound.drainAll()

            env.tick()
            assertEquals(road1, env.me().roomId, "ride stops where combat began")
            assertNull(env.me().ridingMountId)
            val texts = env.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(texts.any { it.contains("cut short", ignoreCase = true) }, "got=$texts")
        }

        @Test
        fun `moving by other means cancels the ride quietly`() = runTest {
            val env = env()
            env.ownMount()
            env.explored(listOf(stable, road1, road2, gate))

            env.system.requestTravel(env.sid, gate.value)
            env.tick()
            // Teleport elsewhere (recall, staff goto, ...).
            env.players.moveTo(env.sid, gate)
            env.outbound.drainAll()

            env.tick()
            assertNull(env.me().ridingMountId)
            assertTrue(!env.system.isRiding(env.sid))
        }
    }

    @Nested
    inner class Speeds {
        @Test
        fun `a faster mount covers each room in msPerRoom over its speed`() = runTest {
            val env = env()
            env.ownFlyingMount() // storm_gryphon, speed 2.0 -> 150ms per hop
            env.explored(listOf(stable, road1, road2, gate))

            env.system.requestTravel(env.sid, gate.value)
            env.advance(149)
            assertEquals(stable, env.me().roomId, "not yet due at 149ms")
            env.advance(1)
            assertEquals(road1, env.me().roomId, "gryphon hops every 150ms")
        }

        @Test
        fun `the fastest owned mount is picked by default`() = runTest {
            val env = env()
            env.ownMount()
            env.ownFlyingMount()
            env.explored(listOf(stable, road1, road2, gate))

            env.system.requestTravel(env.sid, gate.value)
            assertEquals("storm_gryphon", env.me().ridingMountId)
        }

        @Test
        fun `an active mount sprite overrides the fastest pick`() = runTest {
            val env = env(spriteRegistry = ponyRegistry())
            env.ownMount()
            env.ownFlyingMount()
            env.explored(listOf(stable, road1, road2, gate))
            env.me().activeSprite = "mount_dappled_pony"

            env.system.requestTravel(env.sid, gate.value)
            assertEquals("dappled_pony", env.me().ridingMountId, "rides the sprite's mount")
            env.advance(150)
            assertEquals(stable, env.me().roomId, "pony pace, not gryphon pace")
            env.advance(150)
            assertEquals(road1, env.me().roomId)
        }
    }

    @Nested
    inner class Flights {
        @Test
        fun `flies to an explored room deep in another zone`() = runTest {
            val env = env()
            env.ownFlyingMount()
            env.explored(listOf(stable, deep))

            env.system.requestTravel(env.sid, deep.value)
            assertEquals("storm_gryphon", env.me().ridingMountId, "airborne on the gryphon")
            assertEquals(stable, env.me().roomId, "takeoff is not arrival")
            assertTrue(env.system.isRiding(env.sid))

            env.advance(FALLBACK_FLIGHT_MS - 1)
            assertEquals(stable, env.me().roomId, "still airborne")

            env.advance(1)
            assertEquals(deep, env.me().roomId, "landed at the destination")
            assertNull(env.me().ridingMountId, "dismounted on landing")
            assertTrue(!env.system.isRiding(env.sid))
            val texts = env.texts()
            assertTrue(texts.any { it.contains("alight", ignoreCase = true) }, "got=$texts")
        }

        @Test
        fun `flight time scales with the zones' world-map distance`() = runTest {
            // Centres: plains (10,10), hills (20,10) -> distance 10% -> 2000 + 80*10 = 2800ms.
            val env = env(
                zoneWorldMap = mapOf(
                    "plains" to ZoneWorldMap(x = 0.0, y = 0.0, w = 20.0, h = 20.0),
                    "hills" to ZoneWorldMap(x = 10.0, y = 0.0, w = 20.0, h = 20.0),
                ),
            )
            env.ownFlyingMount()
            env.explored(listOf(stable, deep))

            env.system.requestTravel(env.sid, deep.value)
            env.advance(2799)
            assertEquals(stable, env.me().roomId, "still airborne at 2799ms")
            env.advance(1)
            assertEquals(deep, env.me().roomId, "landed at 2800ms")
        }

        @Test
        fun `a ground route is preferred over flying when one is known`() = runTest {
            val env = env()
            env.ownFlyingMount()
            env.explored(listOf(stable, road1, road2, gate))

            env.system.requestTravel(env.sid, gate.value)
            env.advance(150)
            assertEquals(road1, env.me().roomId, "rode the first hop instead of taking off")
        }

        @Test
        fun `a non-flying active sprite still flies on the owned flying mount`() = runTest {
            val env = env(spriteRegistry = ponyRegistry())
            env.ownMount()
            env.ownFlyingMount()
            env.explored(listOf(stable, deep))
            env.me().activeSprite = "mount_dappled_pony"

            env.system.requestTravel(env.sid, deep.value)
            assertEquals("storm_gryphon", env.me().ridingMountId, "ponies cannot fly")
        }

        @Test
        fun `an unexplored destination cannot be flown to`() = runTest {
            val env = env()
            env.ownFlyingMount()
            env.explored(listOf(stable))

            env.system.requestTravel(env.sid, deep.value)
            env.assertError("explored")
        }

        @Test
        fun `combat at landing time cancels the flight in place`() = runTest {
            val env = env()
            env.ownFlyingMount()
            env.explored(listOf(stable, deep))

            env.system.requestTravel(env.sid, deep.value)
            env.mobs.upsert(MobState(MobId("plains:bandit"), "a bandit", stable, hp = 10, maxHp = 10))
            assertNull(env.combat.startCombat(env.sid, "bandit"))
            env.outbound.drainAll()

            env.advance(FALLBACK_FLIGHT_MS)
            assertEquals(stable, env.me().roomId, "never left the ground")
            assertNull(env.me().ridingMountId)
            val texts = env.texts()
            assertTrue(texts.any { it.contains("cut short", ignoreCase = true) }, "got=$texts")
        }

        @Test
        fun `moving by other means cancels the flight quietly`() = runTest {
            val env = env()
            env.ownFlyingMount()
            env.explored(listOf(stable, road1, deep))

            env.system.requestTravel(env.sid, deep.value)
            env.players.moveTo(env.sid, road1)
            env.outbound.drainAll()

            env.advance(FALLBACK_FLIGHT_MS)
            assertEquals(road1, env.me().roomId, "stays where they walked")
            assertNull(env.me().ridingMountId)
            assertTrue(!env.system.isRiding(env.sid))
        }

        @Test
        fun `a new travel request supersedes the flight`() = runTest {
            val env = env()
            env.ownFlyingMount()
            env.explored(listOf(stable, road1, road2, gate, deep))

            env.system.requestTravel(env.sid, deep.value)
            // Re-target to a ground destination before the landing fires.
            env.system.requestTravel(env.sid, gate.value)
            env.advance(FALLBACK_FLIGHT_MS)
            assertTrue(env.me().roomId.zone == "plains", "old landing never fired; got=${env.me().roomId}")
        }
    }

    @Nested
    inner class MountSprites {
        private fun mountRegistry(): SpriteRegistry {
            val reg = SpriteRegistry()
            reg.register(
                SpriteDefinition(
                    id = "dappled_pony",
                    displayName = "Dappled Pony",
                    category = SpriteCategory.MOUNT,
                    requirements = listOf(SpriteRequirement.Mount("dappled_pony")),
                    variants = listOf(
                        SpriteVariant(
                            imageId = "mount_dappled_pony",
                            displayName = "Dappled Pony",
                            imagePath = "player_sprites/mount_dappled_pony.png",
                        ),
                    ),
                ),
            )
            return reg
        }

        @Test
        fun `mount sprite unlocks only with ownership`() {
            val reg = mountRegistry()
            assertNull(
                reg.validateSelection(
                    "mount_dappled_pony",
                    level = 50,
                    unlockedAchievementIds = emptySet(),
                    isStaff = false,
                    playerRace = "HUMAN",
                    playerClass = "WARRIOR",
                    playerGender = "enby",
                ),
                "locked without ownership",
            )
            assertNotNull(
                reg.validateSelection(
                    "mount_dappled_pony",
                    level = 1,
                    unlockedAchievementIds = emptySet(),
                    isStaff = false,
                    playerRace = "HUMAN",
                    playerClass = "WARRIOR",
                    playerGender = "enby",
                    ownedMounts = setOf("dappled_pony"),
                ),
                "unlocked once owned",
            )
        }

        @Test
        fun `mount sprites are never auto-resolved`() {
            val reg = mountRegistry()
            assertNull(reg.autoResolve(level = 100, isStaff = false, playerRace = "HUMAN", playerClass = "WARRIOR", playerGender = "enby"))
        }

        @Test
        fun `mountSprite finds the definition for a mount id`() {
            val reg = mountRegistry()
            assertEquals("Dappled Pony", reg.mountSprite("dappled_pony")?.displayName)
            assertNull(reg.mountSprite("unknown_mount"))
        }
    }

    private class Env(
        val world: World,
        val players: PlayerRegistry,
        val mobs: MobRegistry,
        val outbound: LocalOutboundBus,
        val combat: CombatSystem,
        val clock: MutableClock,
        val scheduler: Scheduler,
        val system: MountTravelSystem,
        val sid: SessionId,
        val msPerRoom: Long,
    ) {
        fun me() = players.get(sid)!!

        fun ownMount() {
            me().ownedMounts.add("dappled_pony")
        }

        fun ownFlyingMount() {
            me().ownedMounts.add("storm_gryphon")
        }

        fun explored(rooms: List<RoomId>) {
            me().exploredRooms = rooms.map { it.value }.toMutableSet()
        }

        /** Advances one ride interval and runs due scheduler actions. */
        suspend fun tick() {
            clock.advance(msPerRoom)
            scheduler.runDue()
        }

        /** Advances an arbitrary interval and runs due scheduler actions. */
        suspend fun advance(ms: Long) {
            clock.advance(ms)
            scheduler.runDue()
        }

        fun assertError(fragment: String) {
            val errors = outbound.drainAll().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains(fragment, ignoreCase = true) }, "expected error containing '$fragment', got=$errors")
        }

        fun texts(): List<String> = outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
    }

    /** A registry holding just the (non-flying) pony's mount sprite. */
    private fun ponyRegistry(): SpriteRegistry {
        val reg = SpriteRegistry()
        reg.register(
            SpriteDefinition(
                id = "dappled_pony",
                displayName = "Dappled Pony",
                category = SpriteCategory.MOUNT,
                requirements = listOf(SpriteRequirement.Mount("dappled_pony")),
                variants = listOf(
                    SpriteVariant(
                        imageId = "mount_dappled_pony",
                        displayName = "Dappled Pony",
                        imagePath = "player_sprites/mount_dappled_pony.png",
                    ),
                ),
            ),
        )
        return reg
    }

    private suspend fun env(
        zoneWorldMap: Map<String, ZoneWorldMap> = emptyMap(),
        spriteRegistry: SpriteRegistry? = null,
    ): Env {
        val world = travelWorld(zoneWorldMap)
        val players = buildTestPlayerRegistry(world.startRoom)
        val mobs = MobRegistry()
        val items = ItemRegistry()
        val outbound = LocalOutboundBus()
        val combat = CombatSystem(players, mobs, items, outbound)
        val clock = MutableClock(0L)
        val scheduler = Scheduler(clock)
        val config = MountTravelConfig(
            msPerRoom = 300L,
            flightMsBase = 2000L,
            flightMsPerMapPercent = 80L,
            flightMsMin = 2000L,
            flightMsMax = 10000L,
        )
        val system = MountTravelSystem(
            players = players,
            world = world,
            outbound = outbound,
            combat = combat,
            scheduler = scheduler,
            spriteRegistry = spriteRegistry,
            config = config,
        )
        val sid = SessionId(1)
        players.loginOrFail(sid, "Rider")
        outbound.drainAll()
        return Env(world, players, mobs, outbound, combat, clock, scheduler, system, sid, config.msPerRoom)
    }

    companion object {
        private val stable = RoomId("plains:stable")
        private val road1 = RoomId("plains:road1")
        private val road2 = RoomId("plains:road2")
        private val gate = RoomId("plains:gate")
        private val border = RoomId("hills:border")
        private val deep = RoomId("hills:deep")

        /** With flightMsBase=2000 and no placements, flights fall back to (min+max)/2. */
        private const val FALLBACK_FLIGHT_MS = 6000L

        private fun mountItem(
            id: String,
            mountId: String,
            speed: Double,
            flying: Boolean,
        ): ItemSpawn =
            ItemSpawn(
                instance = ItemInstance(
                    id = ItemId(id),
                    item = Item(
                        keyword = mountId,
                        displayName = mountId,
                        itemType = ItemType.MOUNT,
                        mountId = mountId,
                        mountSpeed = speed,
                        flying = flying,
                        basePrice = 100,
                    ),
                ),
            )

        /**
         * Linear chain stable =e= road1 =e= road2 =e= gate =e= hills:border =e= hills:deep.
         * The border room is one hop into the adjacent zone (a valid ground destination);
         * deep is two hops in (only reachable on wings). The shop items define two mounts:
         * a 1.0x ground pony and a 2.0x flying gryphon.
         */
        private fun travelWorld(zoneWorldMap: Map<String, ZoneWorldMap> = emptyMap()): World {
            val rooms = mapOf(
                stable to Room(stable, "Stable", "A stable.", mapOf(Direction.EAST to road1)),
                road1 to Room(road1, "Road West", "A road.", mapOf(Direction.WEST to stable, Direction.EAST to road2)),
                road2 to Room(road2, "Road East", "A road.", mapOf(Direction.WEST to road1, Direction.EAST to gate)),
                gate to Room(gate, "Zone Gate", "A gate.", mapOf(Direction.WEST to road2, Direction.EAST to border)),
                border to Room(border, "Hills Border", "A border.", mapOf(Direction.WEST to gate, Direction.EAST to deep)),
                deep to Room(deep, "Deep Hills", "Deep hills.", mapOf(Direction.WEST to border)),
            )
            return World(
                rooms = rooms,
                startRoom = stable,
                itemSpawns = listOf(
                    mountItem("plains:pony_item", "dappled_pony", speed = 1.0, flying = false),
                    mountItem("plains:gryphon_item", "storm_gryphon", speed = 2.0, flying = true),
                ),
                zoneWorldMap = zoneWorldMap,
            )
        }
    }
}
