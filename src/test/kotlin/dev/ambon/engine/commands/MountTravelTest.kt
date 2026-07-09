package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.MountTravelConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.sprite.SpriteCategory
import dev.ambon.domain.sprite.SpriteDefinition
import dev.ambon.domain.sprite.SpriteRequirement
import dev.ambon.domain.sprite.SpriteVariant
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
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
            // Destination explored, but the middle of the only route is not.
            env.explored(listOf(stable, road1, gate))
            env.system.requestTravel(env.sid, gate.value)
            env.assertError("route")
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
        fun `rooms deeper in an adjacent zone are unreachable`() = runTest {
            val env = env()
            env.ownMount()
            env.explored(listOf(stable, road1, road2, gate, border, deep))

            env.system.requestTravel(env.sid, deep.value)
            env.assertError("route")
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
                    "mount_dappled_pony", level = 50, unlockedAchievementIds = emptySet(),
                    isStaff = false, playerRace = "HUMAN", playerClass = "WARRIOR", playerGender = "enby",
                ),
                "locked without ownership",
            )
            assertNotNull(
                reg.validateSelection(
                    "mount_dappled_pony", level = 1, unlockedAchievementIds = emptySet(),
                    isStaff = false, playerRace = "HUMAN", playerClass = "WARRIOR", playerGender = "enby",
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

        fun explored(rooms: List<RoomId>) {
            me().exploredRooms = rooms.map { it.value }.toMutableSet()
        }

        /** Advances one ride interval and runs due scheduler actions. */
        suspend fun tick() {
            clock.advance(msPerRoom)
            scheduler.runDue()
        }

        fun assertError(fragment: String) {
            val errors = outbound.drainAll().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains(fragment, ignoreCase = true) }, "expected error containing '$fragment', got=$errors")
        }
    }

    private suspend fun env(): Env {
        val world = travelWorld()
        val players = buildTestPlayerRegistry(world.startRoom)
        val mobs = MobRegistry()
        val items = ItemRegistry()
        val outbound = LocalOutboundBus()
        val combat = CombatSystem(players, mobs, items, outbound)
        val clock = MutableClock(0L)
        val scheduler = Scheduler(clock)
        val config = MountTravelConfig(msPerRoom = 300L)
        val system = MountTravelSystem(
            players = players,
            world = world,
            outbound = outbound,
            combat = combat,
            scheduler = scheduler,
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

        /**
         * Linear chain stable =e= road1 =e= road2 =e= gate =e= hills:border =e= hills:deep.
         * The border room is one hop into the adjacent zone (a valid destination); deep is
         * two hops in (never routable from plains).
         */
        private fun travelWorld(): World {
            val rooms = mapOf(
                stable to Room(stable, "Stable", "A stable.", mapOf(Direction.EAST to road1)),
                road1 to Room(road1, "Road West", "A road.", mapOf(Direction.WEST to stable, Direction.EAST to road2)),
                road2 to Room(road2, "Road East", "A road.", mapOf(Direction.WEST to road1, Direction.EAST to gate)),
                gate to Room(gate, "Zone Gate", "A gate.", mapOf(Direction.WEST to road2, Direction.EAST to border)),
                border to Room(border, "Hills Border", "A border.", mapOf(Direction.WEST to gate, Direction.EAST to deep)),
                deep to Room(deep, "Deep Hills", "Deep hills.", mapOf(Direction.WEST to border)),
            )
            return World(rooms = rooms, startRoom = stable)
        }
    }
}
