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
import dev.ambon.domain.mob.MobRole
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
        fun `arcanum parses an optional page after the section`() {
            assertEquals(Command.Arcanum("mobs", 2), CommandParser.parse("arcanum mobs 2"))
            assertEquals(Command.Arcanum("rooms", 10), CommandParser.parse("arcanum rooms 10"))
            assertEquals(Command.Arcanum("items", 1), CommandParser.parse("journal items 1"))
        }

        @Test
        fun `arcanum with a non-numeric page is invalid`() {
            assertTrue(CommandParser.parse("arcanum mobs two") is Command.Invalid)
            assertTrue(CommandParser.parse("arcanum mobs 2 extra") is Command.Invalid)
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

        @Test
        fun `consider shows illumination odds while pledged`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.router.handle(sid, Command.Pledge)
            h.mobs.upsert(MobState(id = MobId("test:rat"), name = "rat", roomId = shrineRoom))
            h.drain()

            h.router.handle(sid, Command.Consider("rat"))

            val texts = h.drain().filterIsInstance<OutboundEvent.SendText>().map { it.text }
            // Base stats, equal level: the configured base chance of 70%.
            assertTrue(texts.any { it.contains("70% chance to illuminate") }, "got=$texts")
        }

        @Test
        fun `consider output is unchanged for the unpledged`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.mobs.upsert(MobState(id = MobId("test:rat"), name = "rat", roomId = shrineRoom))
            h.drain()

            h.router.handle(sid, Command.Consider("rat"))

            val events = h.drain()
            val texts = events.filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(texts.any { it.contains("Estimated win chance") }, "got=$texts")
            assertFalse(texts.any { it.contains("illuminate") }, "unpledged consider must not mention illumination: got=$texts")
        }

        @Test
        fun `consider on a non-combatant tells the pledged that observation always succeeds`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.router.handle(sid, Command.Pledge)
            h.mobs.upsert(
                MobState(id = MobId("test:elder"), name = "the village elder", roomId = shrineRoom, role = MobRole.DIALOG),
            )
            h.drain()

            h.router.handle(sid, Command.Consider("elder"))

            val events = h.drain()
            val infos = events.filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("observing them for your Arcanum always succeeds") }, "got=$infos")
            assertTrue(events.filterIsInstance<OutboundEvent.SendError>().isEmpty(), "pledged consider on an NPC is not an error")
        }

        @Test
        fun `consider on a non-combatant still errors for the unpledged`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.mobs.upsert(
                MobState(id = MobId("test:elder"), name = "the village elder", roomId = shrineRoom, role = MobRole.DIALOG),
            )
            h.drain()

            h.router.handle(sid, Command.Consider("elder"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("isn't a threat") }, "got=$errors")
        }

        @Test
        fun `spells leafs through the Arcanum for an Akathavae`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.router.handle(sid, Command.Pledge)
            h.drain()

            h.router.handle(sid, Command.Spells)

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("Arcanum") }, "got=$infos")
            assertFalse(infos.any { it.contains("don't know any spells") }, "got=$infos")
        }
    }

    // ── Class switch tests ───────────────────────────────────────────────

    @Nested
    inner class ClassSwitch {
        /** Harness whose progression resolves real class scaling, so vitals visibly rescale. */
        private fun classHarness(config: AkathavaeConfig = AkathavaeConfig()): CommandRouterHarness {
            val classRegistry = dev.ambon.engine.PlayerClassRegistry().also { reg ->
                dev.ambon.engine.PlayerClassRegistryLoader.load(dev.ambon.test.testClassEngineConfig(), reg)
            }
            val progression = dev.ambon.engine.PlayerProgression(classRegistry = classRegistry)
            val world = shrineWorld()
            val players = buildTestPlayerRegistry(
                world.startRoom,
                progression = progression,
                classRegistry = classRegistry,
            )
            return CommandRouterHarness.create(
                world = world,
                players = players,
                progression = progression,
                classRegistry = classRegistry,
                akathavaeConfig = config,
            )
        }

        @Test
        fun `pledging switches the class to Akathavae and rescales vitals`() = runTest {
            val h = classHarness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            val me = h.players.get(sid)!!
            h.players.setLevel(sid, 10)
            val warriorMaxHp = me.maxHp
            h.drain()

            h.router.handle(sid, Command.Pledge)

            assertEquals("AKATHAVAE", me.playerClass)
            assertEquals("WARRIOR", me.preAkathavaeClass)
            assertTrue(me.unlockedClasses.contains("AKATHAVAE"))
            // Test config: Akathavae HP curve (1.30) is gentler than Warrior (1.80).
            assertTrue(me.maxHp < warriorMaxHp, "expected vitals rescaled, was $warriorMaxHp now ${me.maxHp}")
            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("set aside the ways of the Warrior") }, "got=$infos")
        }

        @Test
        fun `renouncing restores the former class and vitals`() = runTest {
            val h = classHarness(config = AkathavaeConfig(renounceCostGold = 0))
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            val me = h.players.get(sid)!!
            h.players.setLevel(sid, 10)
            val warriorMaxHp = me.maxHp
            h.router.handle(sid, Command.Pledge)
            h.drain()

            h.router.handle(sid, Command.Renounce(confirm = true))

            assertEquals("WARRIOR", me.playerClass)
            assertNull(me.preAkathavaeClass)
            assertFalse(me.unlockedClasses.contains("AKATHAVAE"))
            assertEquals(warriorMaxHp, me.maxHp, "vitals should rescale back to the Warrior curve")
            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("ways of the Warrior once more") }, "got=$infos")
        }

        @Test
        fun `pledging sets aside the former class's abilities`() = runTest {
            val classRegistry = dev.ambon.engine.PlayerClassRegistry().also { reg ->
                dev.ambon.engine.PlayerClassRegistryLoader.load(dev.ambon.test.testClassEngineConfig(), reg)
            }
            val progression = dev.ambon.engine.PlayerProgression(classRegistry = classRegistry)
            val world = shrineWorld()
            val outbound = dev.ambon.bus.LocalOutboundBus()
            val items = dev.ambon.engine.items.ItemRegistry()
            val mobs = dev.ambon.engine.MobRegistry()
            val players = buildTestPlayerRegistry(
                world.startRoom,
                items = items,
                progression = progression,
                classRegistry = classRegistry,
            )
            val combat = dev.ambon.engine.CombatSystem(players, mobs, items, outbound)
            val registry = dev.ambon.engine.abilities.AbilityRegistry()
            registry.register(
                dev.ambon.engine.abilities.AbilityDefinition(
                    id = dev.ambon.engine.abilities.AbilityId("cleave"),
                    displayName = "Cleave",
                    description = "A mighty swing.",
                    manaCostPct = 10.0,
                    cooldownMs = 0,
                    levelRequired = 1,
                    skillPointCost = 0,
                    requiredClass = "WARRIOR",
                    targetType = "enemy",
                    effect = dev.ambon.engine.abilities.AbilityEffect.DirectDamage(dev.ambon.domain.DamageRange(5, 5)),
                ),
            )
            val abilitySystem = dev.ambon.engine.abilities.AbilitySystem(
                players = players,
                registry = registry,
                outbound = outbound,
                combat = combat,
                clock = MutableClock(0L),
            )
            val h = CommandRouterHarness.create(
                world = world,
                players = players,
                items = items,
                mobs = mobs,
                outbound = outbound,
                progression = progression,
                classRegistry = classRegistry,
                abilitySystem = abilitySystem,
                akathavaeConfig = AkathavaeConfig(),
            )
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            val me = h.players.get(sid)!!
            // A Warrior auto-learns the zero-cost class ability.
            abilitySystem.refreshKnownAbilities(sid)
            assertTrue(
                abilitySystem.knownAbilities(sid).any { it.id.value == "cleave" },
                "warrior should know cleave before pledging",
            )
            h.drain()

            h.router.handle(sid, Command.Pledge)

            assertTrue(me.isAkathavae)
            assertFalse(
                abilitySystem.knownAbilities(sid).any { it.id.value == "cleave" },
                "the former class's abilities are set aside under the pledge",
            )
        }

        @Test
        fun `a multiclassed player keeps other unlocks and returns to the pre-pledge class`() = runTest {
            val h = classHarness(config = AkathavaeConfig(renounceCostGold = 0))
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            val me = h.players.get(sid)!!
            me.unlockedClasses.add("MAGE")
            h.drain()

            h.router.handle(sid, Command.Pledge)
            assertEquals(setOf("WARRIOR", "MAGE", "AKATHAVAE"), me.unlockedClasses)
            h.router.handle(sid, Command.Renounce(confirm = true))

            assertEquals("WARRIOR", me.playerClass)
            assertEquals(setOf("WARRIOR", "MAGE"), me.unlockedClasses)
        }
    }

    // ── Multiclass lockout ───────────────────────────────────────────────

    @Nested
    inner class MulticlassLockout {
        @Test
        fun `train unlock is refused while pledged`() = runTest {
            val trainerRoom = RoomId("test:trainer")
            val world = dev.ambon.domain.world.World(
                rooms = mapOf(
                    trainerRoom to Room(id = trainerRoom, title = "Hall of Mentors", description = "Trainers.", exits = emptyMap()),
                ),
                startRoom = trainerRoom,
            )
            val items = dev.ambon.engine.items.ItemRegistry()
            val players = buildTestPlayerRegistry(trainerRoom, items = items)
            val mobs = dev.ambon.engine.MobRegistry()
            val outbound = dev.ambon.bus.LocalOutboundBus()
            val combat = dev.ambon.engine.CombatSystem(players, mobs, items, outbound)
            val abilitySystem = dev.ambon.engine.abilities.AbilitySystem(
                players = players,
                registry = dev.ambon.engine.abilities.AbilityRegistry(),
                outbound = outbound,
                combat = combat,
                clock = MutableClock(0L),
            )
            val trainerRegistry = dev.ambon.engine.TrainerRegistry()
            trainerRegistry.register(
                listOf(
                    dev.ambon.domain.world.TrainerDefinition(
                        id = "polyglot",
                        name = "Master Polyglot",
                        classNames = listOf("MAGE", "ROGUE"),
                        roomId = trainerRoom,
                    ),
                ),
            )
            val ctx = dev.ambon.engine.commands.handlers.EngineContext(
                players = players,
                mobs = mobs,
                world = world,
                items = items,
                outbound = outbound,
                combat = combat,
                gmcpEmitter = null,
                worldState = null,
            )
            val router = CommandRouter(outbound = outbound, players = players)
            dev.ambon.engine.commands.handlers.TrainerHandler(
                ctx = ctx,
                abilitySystem = abilitySystem,
                trainerRegistry = trainerRegistry,
                multiclassConfig = dev.ambon.config.MulticlassConfig(minLevel = 1, goldCost = 0),
            ).register(router)

            val sid = SessionId(1L)
            players.loginOrFail(sid, "Thalen")
            val me = players.get(sid)!!
            me.level = 20
            me.gold = 10_000L
            me.isAkathavae = true
            outbound.drainAll()

            router.handle(sid, Command.Train.Unlock(className = "mage"))

            val errors = outbound.drainAll().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(
                errors.any { it.contains("pledged yourself to knowledge, not combat and glory") },
                "got=$errors",
            )
            assertFalse(me.unlockedClasses.contains("MAGE"))
        }
    }

    // ── Journal (arcanum command) tests ──────────────────────────────────

    @Nested
    inner class Journal {
        /** 100 entries per telnet page (MAX_SECTION_ROWS × 4 per row). */
        private val pageSize = 100

        private suspend fun pledgedWithMobEntries(h: CommandRouterHarness, sid: SessionId, count: Int) {
            h.loginPlayer(sid, "Thalen")
            val me = h.players.get(sid)!!
            me.isAkathavae = true
            for (i in 1..count) {
                me.arcanum.mobs["test:mob_${"%03d".format(i)}"] = ArcanumEntry(firstRecordedAtMs = i.toLong())
            }
            h.drain()
        }

        @Test
        fun `bare arcanum mobs shows page 1 with a paging footer`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            pledgedWithMobEntries(h, sid, count = 150)

            h.router.handle(sid, Command.Arcanum("mobs"))

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("Creatures (150)") }, "got=$infos")
            assertTrue(infos.any { it.contains("mob 001") }, "page 1 should start at the first entry; got=$infos")
            assertFalse(infos.any { it.contains("mob 150") }, "page 1 must not spill into page 2; got=$infos")
            assertTrue(infos.any { it.contains("Page 1/2 — 'arcanum mobs 2' for more.") }, "got=$infos")
        }

        @Test
        fun `arcanum mobs 2 shows the second page`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            pledgedWithMobEntries(h, sid, count = 150)

            h.router.handle(sid, Command.Arcanum("mobs", 2))

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("mob 101") }, "page 2 starts after the first $pageSize; got=$infos")
            assertTrue(infos.any { it.contains("mob 150") }, "got=$infos")
            assertFalse(infos.any { it.contains("mob 001") }, "page 2 must not repeat page 1; got=$infos")
            assertTrue(infos.any { it.contains("Page 2/2.") }, "the last page has no next-page hint; got=$infos")
        }

        @Test
        fun `out-of-range page is a friendly error`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            pledgedWithMobEntries(h, sid, count = 150)

            h.router.handle(sid, Command.Arcanum("mobs", 5))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("run 1 to 2") && it.contains("arcanum mobs 2") }, "got=$errors")
        }

        @Test
        fun `a single page renders without a paging footer`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            pledgedWithMobEntries(h, sid, count = 8)

            h.router.handle(sid, Command.Arcanum("mobs"))

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertFalse(infos.any { it.contains("Page 1/") }, "got=$infos")
        }

        @Test
        fun `summary zone lines include the items count`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Thalen")
            h.items.loadSpawns(
                listOf(
                    dev.ambon.domain.world.ItemSpawn(
                        instance = ItemInstance(ItemId("test:hood"), Item(keyword = "hood", displayName = "a leather hood")),
                    ),
                    dev.ambon.domain.world.ItemSpawn(
                        instance = ItemInstance(ItemId("test:ring"), Item(keyword = "ring", displayName = "a copper ring")),
                    ),
                ),
            )
            val me = h.players.get(sid)!!
            me.isAkathavae = true
            me.arcanum.rooms[shrineRoom.value] = ArcanumEntry(firstRecordedAtMs = 1L)
            me.arcanum.items["test:hood"] = ArcanumEntry(firstRecordedAtMs = 1L)
            h.drain()

            h.router.handle(sid, Command.Arcanum(null))

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(
                infos.any { it.contains("test: 1/2 places, 0/0 creatures, 1/2 items.") },
                "zone line should count all three kinds; got=$infos",
            )
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
