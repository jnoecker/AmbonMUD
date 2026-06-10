package dev.ambon.engine.commands

import dev.ambon.config.AkathavaeConfig
import dev.ambon.domain.arcanum.ArcanumEntry
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.MutableClock
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AkathavaeCommandTest {
    // ── Parser tests ─────────────────────────────────────────────────────

    @Nested
    inner class Parser {
        @Test
        fun `pledge parses to Pledge`() {
            assertEquals(Command.Pledge, CommandParser.parse("pledge"))
        }

        @Test
        fun `renounce parses to unconfirmed Renounce`() {
            assertEquals(Command.Renounce(confirm = false), CommandParser.parse("renounce"))
        }

        @Test
        fun `renounce confirm parses to confirmed Renounce`() {
            assertEquals(Command.Renounce(confirm = true), CommandParser.parse("renounce confirm"))
        }

        @Test
        fun `illuminate parses with target`() {
            assertEquals(Command.Illuminate("wisp"), CommandParser.parse("illuminate wisp"))
            assertEquals(Command.Illuminate("wisp"), CommandParser.parse("illum wisp"))
        }

        @Test
        fun `illuminate without target is invalid`() {
            assertTrue(CommandParser.parse("illuminate") is Command.Invalid)
        }

        @Test
        fun `arcanum parses with and without section`() {
            assertEquals(Command.Arcanum(null), CommandParser.parse("arcanum"))
            assertEquals(Command.Arcanum("mobs"), CommandParser.parse("arcanum mobs"))
            assertEquals(Command.Arcanum(null), CommandParser.parse("journal"))
        }

        @Test
        fun `wardrobe parses with and without keyword`() {
            assertEquals(Command.Wardrobe(null), CommandParser.parse("wardrobe"))
            assertEquals(Command.Wardrobe("hood"), CommandParser.parse("wardrobe hood"))
        }
    }

    // ── Router tests ─────────────────────────────────────────────────────

    companion object {
        private val shrineRoom = RoomId("test:shrine")
        private val plainRoom = RoomId("test:plain")
    }

    private fun shrineWorld(): World {
        val rooms = mapOf(
            shrineRoom to Room(
                id = shrineRoom,
                title = "Alcove of the Akathavae",
                description = "A quiet shrine.",
                exits = mapOf(Direction.SOUTH to plainRoom),
                akathavaeShrine = true,
            ),
            plainRoom to Room(
                id = plainRoom,
                title = "A Plain Room",
                description = "Nothing sacred here.",
                exits = mapOf(Direction.NORTH to shrineRoom),
            ),
        )
        return World(rooms = rooms, startRoom = shrineRoom)
    }

    private fun harness(
        config: AkathavaeConfig = AkathavaeConfig(),
        clock: MutableClock = MutableClock(0L),
        startRoom: RoomId = shrineRoom,
    ): CommandRouterHarness {
        val world = shrineWorld()
        val players = buildTestPlayerRegistry(startRoom, clock = clock)
        return CommandRouterHarness.create(
            world = world,
            players = players,
            akathavaeConfig = config,
            clock = clock,
        )
    }

    @Nested
    inner class Router {
        @Test
        fun `pledge at shrine marks player as Akathavae`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.drain()

            h.router.handle(sid, Command.Pledge)

            val me = h.players.get(sid)!!
            assertTrue(me.isAkathavae, "Expected player to be Akathavae after pledging")
            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("Pledge of the Akathavae") }, "got=$infos")
        }

        @Test
        fun `pledge away from shrine is refused`() = runTest {
            val h = harness(startRoom = plainRoom)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.drain()

            h.router.handle(sid, Command.Pledge)

            assertFalse(h.players.get(sid)!!.isAkathavae)
            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("shrine") }, "got=$errors")
        }

        @Test
        fun `pledge twice reports already pledged`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.router.handle(sid, Command.Pledge)
            h.drain()

            h.router.handle(sid, Command.Pledge)

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("already") }, "got=$infos")
        }

        @Test
        fun `renounce without confirm warns and keeps the pledge`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.router.handle(sid, Command.Pledge)
            h.players.get(sid)!!.gold = 10_000L
            h.drain()

            h.router.handle(sid, Command.Renounce(confirm = false))

            val me = h.players.get(sid)!!
            assertTrue(me.isAkathavae, "Unconfirmed renounce must not break the pledge")
            assertEquals(10_000L, me.gold)
            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("renounce confirm") }, "got=$infos")
        }

        @Test
        fun `renounce confirm with insufficient gold is refused`() = runTest {
            val h = harness(config = AkathavaeConfig(renounceCostGold = 2_500))
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.router.handle(sid, Command.Pledge)
            h.players.get(sid)!!.gold = 100L
            h.drain()

            h.router.handle(sid, Command.Renounce(confirm = true))

            val me = h.players.get(sid)!!
            assertTrue(me.isAkathavae)
            assertEquals(100L, me.gold)
            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("2500 gold") }, "got=$errors")
        }

        @Test
        fun `renounce confirm at shrine costs gold and breaks the pledge`() = runTest {
            val h = harness(config = AkathavaeConfig(renounceCostGold = 2_500))
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.router.handle(sid, Command.Pledge)
            h.players.get(sid)!!.gold = 3_000L
            h.drain()

            h.router.handle(sid, Command.Renounce(confirm = true))

            val me = h.players.get(sid)!!
            assertFalse(me.isAkathavae)
            assertEquals(500L, me.gold)
        }

        @Test
        fun `renounce while not pledged is refused`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.drain()

            h.router.handle(sid, Command.Renounce(confirm = true))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("not bound") }, "got=$errors")
        }

        @Test
        fun `re-pledging within the cooldown is refused`() = runTest {
            // Nonzero epoch: renouncedAtMs == 0 is the "never renounced" sentinel.
            val clock = MutableClock(1_000_000L)
            val h = harness(
                config = AkathavaeConfig(renounceCostGold = 0, repledgeCooldownMs = 86_400_000),
                clock = clock,
            )
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.router.handle(sid, Command.Pledge)
            h.router.handle(sid, Command.Renounce(confirm = true))
            clock.advance(3_600_000) // 1h of a 24h cooldown
            h.drain()

            h.router.handle(sid, Command.Pledge)

            assertFalse(h.players.get(sid)!!.isAkathavae)
            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("remembers your renunciation") }, "got=$errors")
        }

        @Test
        fun `re-pledging after the cooldown succeeds`() = runTest {
            val clock = MutableClock(1_000_000L)
            val h = harness(
                config = AkathavaeConfig(renounceCostGold = 0, repledgeCooldownMs = 86_400_000),
                clock = clock,
            )
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.router.handle(sid, Command.Pledge)
            h.router.handle(sid, Command.Renounce(confirm = true))
            clock.advance(86_400_001)
            h.drain()

            h.router.handle(sid, Command.Pledge)

            assertTrue(h.players.get(sid)!!.isAkathavae)
        }

        @Test
        fun `kill is refused while pledged`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.router.handle(sid, Command.Pledge)
            h.mobs.upsert(MobState(id = MobId("test:rat"), name = "rat", roomId = shrineRoom))
            h.drain()

            h.router.handle(sid, Command.Kill("rat"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("pledge stays your hand") }, "got=$errors")
            assertFalse(h.combat.isInCombat(sid), "Pledged player must not enter combat")
        }
    }

    // ── Wardrobe tests ───────────────────────────────────────────────────

    @Nested
    inner class Wardrobe {
        private fun hoodTemplate() = ItemInstance(
            ItemId("test:hood"),
            Item(keyword = "hood", displayName = "a leather hood", slot = ItemSlot.HEAD, armor = 2),
        )

        private suspend fun pledgedWithRecordedHood(h: CommandRouterHarness, sid: SessionId): dev.ambon.engine.PlayerState {
            h.loginPlayer(sid, "Thalen")
            h.items.loadSpawns(listOf(dev.ambon.domain.world.ItemSpawn(instance = hoodTemplate())))
            val me = h.players.get(sid)!!
            me.isAkathavae = true
            me.arcanum.items["test:hood"] = ArcanumEntry(firstRecordedAtMs = 1L)
            h.drain()
            return me
        }

        @Test
        fun `wardrobe conjures a recorded item into its slot`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            pledgedWithRecordedHood(h, sid)

            h.router.handle(sid, Command.Wardrobe("hood"))

            val equipped = h.items.equipment(sid)[ItemSlot.HEAD]
            assertNotNull(equipped, "hood should be equipped")
            assertTrue(equipped!!.item.conjured, "wardrobe items are conjured")
            assertTrue(h.items.inventory(sid).isEmpty(), "conjured items never touch the inventory")
        }

        @Test
        fun `removing a conjured item dissolves it instead of entering the inventory`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            pledgedWithRecordedHood(h, sid)
            h.router.handle(sid, Command.Wardrobe("hood"))
            h.drain()

            h.router.handle(sid, Command.Remove("head"))

            assertNull(h.items.equipment(sid)[ItemSlot.HEAD])
            assertTrue(h.items.inventory(sid).isEmpty(), "dissolved items must not reach the inventory")
            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("dissolves") }, "got=$infos")
        }

        @Test
        fun `wardrobe refuses the unpledged`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Bruiser")
            h.drain()

            h.router.handle(sid, Command.Wardrobe(null))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("pledged") }, "got=$errors")
        }

        @Test
        fun `renouncing dissolves all conjured equipment`() = runTest {
            val h = harness(config = AkathavaeConfig(renounceCostGold = 0))
            val sid = SessionId(1)
            val me = pledgedWithRecordedHood(h, sid)
            h.router.handle(sid, Command.Wardrobe("hood"))
            h.drain()

            h.router.handle(sid, Command.Renounce(confirm = true))

            assertFalse(me.isAkathavae)
            assertNull(h.items.equipment(sid)[ItemSlot.HEAD], "conjured gear cannot outlive the pledge")
            assertTrue(h.items.inventory(sid).isEmpty())
        }
    }

    // ── Discovery hooks ──────────────────────────────────────────────────

    @Nested
    inner class Discovery {
        @Test
        fun `buying from a shop records the item in the Arcanum`() = runTest {
            val world = dev.ambon.domain.world.load.WorldLoader.loadFromResource("world/ok_shop.yaml")
            val items = dev.ambon.engine.items.ItemRegistry()
            items.loadSpawns(world.itemSpawns)
            val players = buildTestPlayerRegistry(world.startRoom, items = items)
            val mobs = dev.ambon.engine.MobRegistry()
            val outbound = dev.ambon.bus.LocalOutboundBus()
            val shopRegistry = dev.ambon.engine.ShopRegistry(items)
            shopRegistry.register(world.shopDefinitions)
            val combat = dev.ambon.engine.CombatSystem(players, mobs, items, outbound)
            val router = buildTestRouter(
                world = world,
                players = players,
                mobs = mobs,
                items = items,
                combat = combat,
                outbound = outbound,
                shopRegistry = shopRegistry,
            )
            val sid = SessionId(1L)
            players.loginOrFail(sid, "Thalen")
            val me = players.get(sid)!!
            me.isAkathavae = true
            me.gold = 10_000L
            outbound.drainAll()

            router.handle(sid, Command.Buy("sword"))

            assertTrue(me.arcanum.items.isNotEmpty(), "purchase should record the item; journal=${me.arcanum.items.keys}")
        }
    }

    // ── Combat-system tests ──────────────────────────────────────────────

    @Nested
    inner class Combat {
        @Test
        fun `startCombat refuses an Akathavae`() = runTest {
            val fixture = CombatTestFixture()
            fixture.mobs.upsert(MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 10, maxHp = 10))
            val combat = fixture.buildCombat()
            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Thalen")
            fixture.players.get(sid)!!.isAkathavae = true

            val err = combat.startCombat(sid, "rat")

            assertNotNull(err)
            assertTrue(err!!.contains("pledge stays your hand"), "got=$err")
        }

        @Test
        fun `an engaged Akathavae takes hits but never swings back`() = runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 10, maxHp = 10)
            fixture.mobs.upsert(mob)
            val combat = fixture.buildCombat(rng = Random(1))
            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Thalen")
            fixture.players.get(sid)!!.isAkathavae = true

            // A mob can still force an Akathavae into combat (aggro / failed illumination).
            combat.engageMobCombat(sid, mob)
            assertTrue(combat.isInCombat(sid))
            fixture.tickCombat(combat)

            val player = fixture.players.get(sid)!!
            assertTrue(player.hp < player.maxHp, "Expected the Akathavae to take damage")
            assertEquals(mob.maxHp, fixture.mobs.get(mob.id)!!.hp, "Akathavae must deal no damage")
        }

        @Test
        fun `non-pledged player still fights normally`() = runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 10, maxHp = 10)
            fixture.mobs.upsert(mob)
            val combat = fixture.buildCombat(rng = Random(1))
            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Bruiser")

            assertNull(combat.startCombat(sid, "rat"))
            fixture.tickCombat(combat)

            assertTrue(fixture.mobs.get(mob.id)!!.hp < mob.maxHp, "Expected normal player to deal damage")
        }
    }
}
