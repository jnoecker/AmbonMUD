package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.StylistConfig
import dev.ambon.domain.DamageRange
import dev.ambon.domain.RaceDef
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.sprite.SpriteCategory
import dev.ambon.domain.sprite.SpriteDefinition
import dev.ambon.domain.sprite.SpriteVariant
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.RaceRegistry
import dev.ambon.engine.SpriteRegistry
import dev.ambon.engine.abilities.AbilityDefinition
import dev.ambon.engine.abilities.AbilityEffect
import dev.ambon.engine.abilities.AbilityId
import dev.ambon.engine.abilities.AbilityRegistry
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.buildTestPlayerRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class StylistCommandTest {
    @Nested
    inner class Parser {
        @Test
        fun `stylist parses to Stylist List`() {
            assertEquals(Command.Stylist.List, CommandParser.parse("stylist"))
        }

        @Test
        fun `changerace parses to ChangeRace with id`() {
            val result = CommandParser.parse("changerace elf")
            assertTrue(result is Command.Stylist.ChangeRace)
            assertEquals("elf", (result as Command.Stylist.ChangeRace).raceId)
        }

        @Test
        fun `changerace without arg returns Invalid`() {
            assertTrue(CommandParser.parse("changerace") is Command.Invalid)
        }
    }

    companion object {
        private val stylistRoom = RoomId("test:stylist")
        private val lobbyRoom = RoomId("test:lobby")

        /**
         * Builds a sprite registry with one per-race tier sprite for HUMAN and ELF so we can
         * observe race-filtered output in `Char.Sprites` GMCP after a stylist race swap.
         */
        private fun spriteRegistryForRaces(): SpriteRegistry {
            val registry = SpriteRegistry()
            registry.register(
                SpriteDefinition(
                    id = "human_base_def",
                    displayName = "Human Base",
                    category = SpriteCategory.TIER,
                    sortOrder = 0,
                    variants = listOf(
                        SpriteVariant(
                            imageId = "human_base",
                            displayName = "Human Base",
                            race = "HUMAN",
                            imagePath = "player_sprites/human_base.png",
                        ),
                    ),
                ),
            )
            registry.register(
                SpriteDefinition(
                    id = "elf_base_def",
                    displayName = "Elf Base",
                    category = SpriteCategory.TIER,
                    sortOrder = 0,
                    variants = listOf(
                        SpriteVariant(
                            imageId = "elf_base",
                            displayName = "Elf Base",
                            race = "ELF",
                            imagePath = "player_sprites/elf_base.png",
                        ),
                    ),
                ),
            )
            return registry
        }

        private fun buildRaceRegistry(): RaceRegistry {
            val registry = RaceRegistry()
            registry.register(
                RaceDef(
                    id = "HUMAN",
                    displayName = "Human",
                    statMods = StatMap.of("STR" to 1, "CHA" to 1),
                ),
            )
            registry.register(
                RaceDef(
                    id = "ELF",
                    displayName = "Elf",
                    statMods = StatMap.of("DEX" to 2, "INT" to 1, "CON" to -1),
                ),
            )
            return registry
        }
    }

    private fun stylistWorld(): World {
        val rooms = mapOf(
            stylistRoom to Room(
                id = stylistRoom,
                title = "Stylist Salon",
                description = "A salon.",
                exits = mapOf(dev.ambon.domain.world.Direction.SOUTH to lobbyRoom),
                stylist = true,
            ),
            lobbyRoom to Room(
                id = lobbyRoom,
                title = "Lobby",
                description = "A lobby.",
                exits = mapOf(dev.ambon.domain.world.Direction.NORTH to stylistRoom),
            ),
        )
        return World(rooms = rooms, startRoom = stylistRoom)
    }

    private fun harness(fee: Long = 500): CommandRouterHarness {
        val world = stylistWorld()
        val players = buildTestPlayerRegistry(world.startRoom)
        return CommandRouterHarness.create(
            world = world,
            players = players,
            stylistConfig = StylistConfig(feeGold = fee),
            raceRegistry = buildRaceRegistry(),
        )
    }

    private data class AbilityHarness(
        val harness: CommandRouterHarness,
        val abilitySystem: AbilitySystem,
        val gmcpEmitter: GmcpEmitter,
    )

    /**
     * Builds a harness with races whose abilities actually exist in the registry,
     * plus a wired-up [AbilitySystem] so race-ability grant/revoke can be observed.
     */
    private fun abilityHarness(fee: Long = 0): AbilityHarness {
        val world = stylistWorld()
        val repo = InMemoryPlayerRepository()
        val items = ItemRegistry()
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val players = buildTestPlayerRegistry(world.startRoom, repo = repo, items = items)
        val combat = CombatSystem(players, mobs, items, outbound)
        val abilityRegistry = AbilityRegistry()
        abilityRegistry.register(
            AbilityDefinition(
                id = AbilityId("human_boon"),
                displayName = "Human Boon",
                description = "A human gift.",
                manaCost = 0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "self",
                effect = AbilityEffect.DirectHeal(minHeal = 1, maxHeal = 1),
            ),
        )
        abilityRegistry.register(
            AbilityDefinition(
                id = AbilityId("elf_grace"),
                displayName = "Elf Grace",
                description = "An elven gift.",
                manaCost = 0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "self",
                effect = AbilityEffect.DirectHeal(minHeal = 1, maxHeal = 1),
            ),
        )
        abilityRegistry.register(
            AbilityDefinition(
                id = AbilityId("shared_trick"),
                displayName = "Shared Trick",
                description = "Something a human can also learn from a trainer.",
                manaCost = 0,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = "enemy",
                effect = AbilityEffect.DirectDamage(damage = DamageRange(1, 1)),
            ),
        )
        val abilitySystem = AbilitySystem(
            players = players,
            registry = abilityRegistry,
            outbound = outbound,
            combat = combat,
            clock = Clock.systemUTC(),
            mobs = mobs,
        )

        val raceRegistry = RaceRegistry()
        raceRegistry.register(
            RaceDef(
                id = "HUMAN",
                displayName = "Human",
                abilities = listOf("human_boon", "shared_trick"),
            ),
        )
        raceRegistry.register(
            RaceDef(
                id = "ELF",
                displayName = "Elf",
                abilities = listOf("elf_grace"),
            ),
        )

        val gmcpEmitter = GmcpEmitter(
            outbound = outbound,
            supportsPackage = { _, _ -> true },
        )

        val harness = CommandRouterHarness.create(
            world = world,
            repo = repo,
            items = items,
            players = players,
            mobs = mobs,
            outbound = outbound,
            stylistConfig = StylistConfig(feeGold = fee),
            raceRegistry = raceRegistry,
            abilitySystem = abilitySystem,
            gmcpEmitter = gmcpEmitter,
        )
        return AbilityHarness(harness, abilitySystem, gmcpEmitter)
    }

    @Nested
    inner class Router {
        @Test
        fun `stylist list requires stylist room`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.roomId = lobbyRoom
            h.drain()

            h.router.handle(sid, Command.Stylist.List)

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("stylist") }, "got=$errors")
        }

        @Test
        fun `stylist list shows races fee and current`() = runTest {
            val h = harness(fee = 500)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.Stylist.List)

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("500 gold") }, "fee not shown: $infos")
            assertTrue(infos.any { it.contains("HUMAN") }, "HUMAN not listed: $infos")
            assertTrue(infos.any { it.contains("ELF") }, "ELF not listed: $infos")
        }

        @Test
        fun `changerace charges fee and updates race`() = runTest {
            val h = harness(fee = 500)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.gold = 1000L
            me.race = "HUMAN"
            me.stats = StatMap.of("STR" to 11, "DEX" to 10, "CON" to 10, "INT" to 10, "WIS" to 10, "CHA" to 11)
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("ELF"))

            h.drain()
            val after = h.players.get(sid)!!
            assertEquals("ELF", after.race)
            assertEquals(500L, after.gold)
            // STR: 11 (base 10 + HUMAN +1) → remove +1 → 10; ELF has no STR mod → stays 10
            assertEquals(10, after.stats["STR"])
            // CHA: 11 → remove HUMAN +1 → 10; ELF has no CHA mod → stays 10
            assertEquals(10, after.stats["CHA"])
            // DEX: 10 → ELF +2 → 12
            assertEquals(12, after.stats["DEX"])
            // CON: 10 → ELF -1 → 9
            assertEquals(9, after.stats["CON"])
            // INT: 10 → ELF +1 → 11
            assertEquals(11, after.stats["INT"])
        }

        @Test
        fun `changerace rejects insufficient gold`() = runTest {
            val h = harness(fee = 500)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.gold = 100L
            me.race = "HUMAN"
            val beforeStats = me.stats
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("ELF"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("500") }, "got=$errors")
            val after = h.players.get(sid)!!
            assertEquals("HUMAN", after.race)
            assertEquals(100L, after.gold)
            assertEquals(beforeStats, after.stats)
        }

        @Test
        fun `changerace rejects unknown race`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.players.get(sid)!!.gold = 1000L
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("DRAGON"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("DRAGON") || it.contains("No such race") }, "got=$errors")
            assertEquals(1000L, h.players.get(sid)!!.gold)
        }

        @Test
        fun `changerace rejects current race`() = runTest {
            val h = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.gold = 1000L
            me.race = "HUMAN"
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("HUMAN"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("already") }, "got=$errors")
            assertEquals(1000L, h.players.get(sid)!!.gold)
        }

        @Test
        fun `changerace recomputes maxHp after stat change`() = runTest {
            val h = harness(fee = 0)
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.race = "HUMAN"
            me.stats = StatMap.of("STR" to 10, "DEX" to 10, "CON" to 20, "INT" to 10, "WIS" to 10, "CHA" to 10)
            me.level = 10
            h.progression.applyLevelStats(me, 10)
            val hpBefore = me.maxHp
            h.drain()

            // Switch HUMAN → ELF: CON -1 will drop the scaling stat
            h.router.handle(sid, Command.Stylist.ChangeRace("ELF"))
            h.drain()

            val after = h.players.get(sid)!!
            assertEquals("ELF", after.race)
            assertEquals(19, after.stats["CON"])
            // CON dropped, so maxHp scaling should decrease (or at least differ)
            assertNotEquals(hpBefore, after.maxHp)
            assertTrue(after.hp <= after.maxHp)
        }

        @Test
        fun `changerace revokes old race abilities and grants new ones`() = runTest {
            val ah = abilityHarness()
            val h = ah.harness
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.race = "HUMAN"
            // Seed the ability system the way login would: race abilities only, no trainer abilities.
            ah.abilitySystem.loadAbilities(
                sessionId = sid,
                learnedIds = emptySet(),
                raceAbilityIds = setOf("human_boon", "shared_trick"),
            )
            assertTrue("human_boon" in ah.abilitySystem.knownAbilityIds(sid))
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("ELF"))
            h.drain()

            val known = ah.abilitySystem.knownAbilityIds(sid)
            assertTrue("elf_grace" in known, "new race ability missing: $known")
            assertFalse("human_boon" in known, "old race ability not revoked: $known")
            assertFalse("shared_trick" in known, "old race ability not revoked: $known")
        }

        @Test
        fun `changerace preserves trainer-learned abilities that overlap with old race`() = runTest {
            val ah = abilityHarness()
            val h = ah.harness
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.race = "HUMAN"
            // Player has shared_trick from both race and trainer.
            me.learnedAbilityIds.add("shared_trick")
            ah.abilitySystem.loadAbilities(
                sessionId = sid,
                learnedIds = me.learnedAbilityIds,
                raceAbilityIds = setOf("human_boon", "shared_trick"),
            )
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("ELF"))
            h.drain()

            val known = ah.abilitySystem.knownAbilityIds(sid)
            assertTrue(
                "shared_trick" in known,
                "trainer-learned ability must survive race swap: $known",
            )
            assertFalse("human_boon" in known)
            assertTrue("elf_grace" in known)
            // learnedAbilityIds itself is untouched.
            assertTrue("shared_trick" in h.players.get(sid)!!.learnedAbilityIds)
        }

        @Test
        fun `changerace emits Char_Sprites GMCP so stylist panel shows new race sprites`() = runTest {
            val world = stylistWorld()
            val outbound = LocalOutboundBus()
            val players = buildTestPlayerRegistry(world.startRoom)
            val spriteRegistry = spriteRegistryForRaces()
            val gmcpEmitter = GmcpEmitter(
                outbound = outbound,
                supportsPackage = { _, _ -> true },
                spriteRegistry = spriteRegistry,
            )
            val h = CommandRouterHarness.create(
                world = world,
                players = players,
                outbound = outbound,
                stylistConfig = StylistConfig(feeGold = 0),
                raceRegistry = buildRaceRegistry(),
                gmcpEmitter = gmcpEmitter,
                spriteRegistry = spriteRegistry,
            )
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.gold = 1000L
            me.race = "HUMAN"
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("ELF"))
            val events = h.drain()

            val charSprites = events.filterIsInstance<OutboundEvent.GmcpData>()
                .filter { it.sessionId == sid && it.gmcpPackage == "Char.Sprites" }
            assertTrue(
                charSprites.isNotEmpty(),
                "Char.Sprites GMCP should be emitted after race change so stylist panel refreshes",
            )
            val payload = charSprites.last().jsonData
            assertTrue(
                "elf_base" in payload,
                "new race sprite missing in payload: $payload",
            )
            assertFalse(
                "human_base" in payload,
                "old race sprite still present in payload: $payload",
            )
        }

        @Test
        fun `changerace resets pinned activeSprite when it is no longer valid for new race`() = runTest {
            val world = stylistWorld()
            val outbound = LocalOutboundBus()
            val players = buildTestPlayerRegistry(world.startRoom)
            val spriteRegistry = spriteRegistryForRaces()
            val gmcpEmitter = GmcpEmitter(
                outbound = outbound,
                supportsPackage = { _, _ -> true },
                spriteRegistry = spriteRegistry,
            )
            val h = CommandRouterHarness.create(
                world = world,
                players = players,
                outbound = outbound,
                stylistConfig = StylistConfig(feeGold = 0),
                raceRegistry = buildRaceRegistry(),
                gmcpEmitter = gmcpEmitter,
                spriteRegistry = spriteRegistry,
            )
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.gold = 1000L
            me.race = "HUMAN"
            me.activeSprite = "human_base"
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("ELF"))
            h.drain()

            assertEquals(
                null,
                h.players.get(sid)!!.activeSprite,
                "activeSprite should be cleared when pinned sprite no longer matches new race",
            )
        }

        @Test
        fun `changerace emits Char_Skills GMCP so client refreshes ability list`() = runTest {
            val ah = abilityHarness()
            val h = ah.harness
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val me = h.players.get(sid)!!
            me.race = "HUMAN"
            ah.abilitySystem.loadAbilities(
                sessionId = sid,
                learnedIds = emptySet(),
                raceAbilityIds = setOf("human_boon", "shared_trick"),
            )
            h.drain()

            h.router.handle(sid, Command.Stylist.ChangeRace("ELF"))
            val events = h.drain()

            val charSkills = events.filterIsInstance<OutboundEvent.GmcpData>()
                .filter { it.sessionId == sid && it.gmcpPackage == "Char.Skills" }
            assertTrue(
                charSkills.isNotEmpty(),
                "Char.Skills GMCP should be emitted after race change so client ability panel refreshes",
            )
            val payload = charSkills.last().jsonData
            assertTrue("elf_grace" in payload, "new race ability missing in payload: $payload")
            assertFalse("human_boon" in payload, "old race ability still in payload: $payload")
        }
    }
}
