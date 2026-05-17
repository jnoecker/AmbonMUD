package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.RespecConfig
import dev.ambon.config.SkillPointsConfig
import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.TrainerDefinition
import dev.ambon.domain.world.World
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.TrainerRegistry
import dev.ambon.engine.abilities.AbilityDefinition
import dev.ambon.engine.abilities.AbilityEffect
import dev.ambon.engine.abilities.AbilityId
import dev.ambon.engine.abilities.AbilityRegistry
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.commands.handlers.EngineContext
import dev.ambon.engine.commands.handlers.TrainerHandler
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.buildTestPlayerRegistry
import dev.ambon.test.drainAll
import dev.ambon.test.errorMessages
import dev.ambon.test.loginOrFail
import dev.ambon.test.textMessages
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrainerRespecTest {
    // â”€â”€ Parser tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Nested
    inner class Parser {
        @Test
        fun `train reset parses to Train Reset`() {
            assertEquals(Command.Train.Reset, CommandParser.parse("train reset"))
        }

        @Test
        fun `train respec parses to Train Reset`() {
            assertEquals(Command.Train.Reset, CommandParser.parse("train respec"))
        }

        @Test
        fun `trainer reset parses to Train Reset`() {
            assertEquals(Command.Train.Reset, CommandParser.parse("trainer reset"))
        }
    }

    // â”€â”€ Router tests â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    companion object {
        private val TRAINER_ROOM = RoomId("test:trainer")
        private val NO_TRAINER_ROOM = RoomId("test:other")
        private val SID = SessionId(1L)
    }

    private lateinit var clock: MutableClock
    private lateinit var outbound: LocalOutboundBus
    private lateinit var repo: InMemoryPlayerRepository
    private lateinit var items: ItemRegistry
    private lateinit var router: CommandRouter
    private lateinit var abilitySystem: AbilitySystem
    private lateinit var abilityRegistry: AbilityRegistry
    private lateinit var players: dev.ambon.engine.PlayerRegistry

    private var respecConfig = RespecConfig(enabled = true, goldCost = 1000, cooldownMs = 3_600_000)
    private val skillPointsConfig = SkillPointsConfig(interval = 2)

    @BeforeEach
    fun setUp() {
        clock = MutableClock(0L)
        outbound = LocalOutboundBus()
        repo = InMemoryPlayerRepository()
        items = ItemRegistry()

        val world = World(
            rooms = mapOf(
                TRAINER_ROOM to Room(
                    id = TRAINER_ROOM,
                    title = "Training Room",
                    description = "A training room.",
                    exits = emptyMap(),
                ),
                NO_TRAINER_ROOM to Room(
                    id = NO_TRAINER_ROOM,
                    title = "Other Room",
                    description = "A room with no trainer.",
                    exits = emptyMap(),
                ),
            ),
            startRoom = TRAINER_ROOM,
        )

        players = buildTestPlayerRegistry(
            startRoom = TRAINER_ROOM,
            repo = repo,
            items = items,
            clock = clock,
        )

        val mobs = MobRegistry()
        val combat = CombatSystem(players, mobs, items, outbound)

        abilityRegistry = AbilityRegistry()
        abilityRegistry.register(
            AbilityDefinition(
                id = AbilityId("fireball"),
                displayName = "Fireball",
                description = "A ball of fire.",
                manaCostPct = 10.0,
                cooldownMs = 5000,
                levelRequired = 2,
                targetType = "ENEMY",
                effect = AbilityEffect.DirectDamage(DamageRange(5, 10)),
                requiredClass = "MAGE",
            ),
        )
        abilityRegistry.register(
            AbilityDefinition(
                id = AbilityId("frostbolt"),
                displayName = "Frostbolt",
                description = "A bolt of frost.",
                manaCostPct = 8.0,
                cooldownMs = 3000,
                levelRequired = 4,
                targetType = "ENEMY",
                effect = AbilityEffect.DirectDamage(DamageRange(3, 8)),
                requiredClass = "MAGE",
            ),
        )
        abilityRegistry.register(
            AbilityDefinition(
                id = AbilityId("archmage_instinct"),
                displayName = "Archmage Instinct",
                description = "A foundational instinct granted automatically.",
                manaCostPct = 0.0,
                cooldownMs = 0,
                levelRequired = 50,
                targetType = "SELF",
                effect = AbilityEffect.DirectHeal(1, 1),
                requiredClass = "MAGE",
                skillPointCost = 0,
            ),
        )

        abilitySystem = AbilitySystem(
            players = players,
            registry = abilityRegistry,
            outbound = outbound,
            combat = combat,
            clock = clock,
        )

        val trainerRegistry = TrainerRegistry()
        trainerRegistry.register(
            listOf(
                TrainerDefinition(
                    id = "mage_trainer",
                    name = "Archmage Zara",
                    classNames = listOf("MAGE"),
                    roomId = TRAINER_ROOM,
                ),
            ),
        )

        val ctx = EngineContext(
            players = players,
            mobs = mobs,
            world = world,
            items = items,
            outbound = outbound,
            combat = combat,
            gmcpEmitter = null,
            worldState = null,
        )

        router = CommandRouter(outbound = outbound, players = players)
        val handler = TrainerHandler(
            ctx = ctx,
            abilitySystem = abilitySystem,
            trainerRegistry = trainerRegistry,
            skillPointsConfig = skillPointsConfig,
            respecConfig = respecConfig,
            clock = clock,
        )
        handler.register(router)
    }

    private suspend fun loginAndSetup(
        gold: Long = 2000L,
        level: Int = 10,
    ) {
        repo.create(
            dev.ambon.persistence.PlayerCreationRequest(
                name = "Tester",
                startRoomId = TRAINER_ROOM,
                nowEpochMs = 0L,
                passwordHash = dev.ambon.test.TestPasswordHasher.hash("password"),
                ansiEnabled = false,
            ),
        )
        players.loginOrFail(SID, "Tester", "password")
        outbound.drainAll()

        val me = players.get(SID)!!
        me.gold = gold
        me.level = level
        me.unlockedClasses.add("MAGE")
        me.playerClass = "MAGE"
    }

    private suspend fun learnAbilities() {
        val me = players.get(SID)!!
        // Learn two abilities
        abilitySystem.loadAbilities(SID, emptySet())
        abilitySystem.learnAbility(
            sessionId = SID,
            abilityId = AbilityId("fireball"),
            level = me.level,
            unlockedClasses = me.unlockedClasses,
            skillPointInterval = skillPointsConfig.interval,
        )
        me.learnedAbilityIds.add("fireball")

        abilitySystem.learnAbility(
            sessionId = SID,
            abilityId = AbilityId("frostbolt"),
            level = me.level,
            unlockedClasses = me.unlockedClasses,
            skillPointInterval = skillPointsConfig.interval,
        )
        me.learnedAbilityIds.add("frostbolt")
    }

    @Nested
    inner class RespecSuccess {
        @Test
        fun `respec refunds skill points and clears learned abilities`() = runTest {
            loginAndSetup(gold = 2000, level = 10)
            learnAbilities()

            val me = players.get(SID)!!
            assertEquals(2, me.learnedAbilityIds.size)
            assertEquals(2, abilitySystem.knownAbilities(SID).size)

            outbound.drainAll()
            router.handle(SID, Command.Train.Reset)

            val events = outbound.drainAll()
            val texts = events.textMessages(SID)
            assertTrue(texts.any { it.contains("abilities have been reset") }, "Expected reset success message, got: $texts")
            assertTrue(texts.any { it.contains("2 abilities removed") }, "Expected count of removed abilities, got: $texts")

            // Verify abilities cleared
            assertEquals(0, me.learnedAbilityIds.size)
            assertEquals(0, abilitySystem.knownAbilities(SID).size)

            // Verify skill points refunded: level 10 / interval 2 = 5 points, 0 learned = 5 available
            assertTrue(texts.any { it.contains("5 skill points") }, "Expected 5 skill points available, got: $texts")
        }

        @Test
        fun `respec keeps zero cost auto learned abilities`() = runTest {
            loginAndSetup(gold = 2000, level = 50)
            abilitySystem.loadAbilities(SID, emptySet())
            learnAbilities()

            assertTrue(
                abilitySystem.knownAbilities(SID).any { it.id.value == "archmage_instinct" },
                "Expected auto-learned ability before reset",
            )

            outbound.drainAll()
            router.handle(SID, Command.Train.Reset)

            val me = players.get(SID)!!
            assertEquals(0, me.learnedAbilityIds.size)
            assertEquals(setOf("archmage_instinct"), abilitySystem.knownAbilities(SID).map { it.id.value }.toSet())
        }

        @Test
        fun `respec deducts gold`() = runTest {
            loginAndSetup(gold = 2000, level = 10)
            learnAbilities()

            val me = players.get(SID)!!
            assertEquals(2000L, me.gold)

            outbound.drainAll()
            router.handle(SID, Command.Train.Reset)

            assertEquals(1000L, me.gold) // 2000 - 1000 cost
        }
    }

    @Nested
    inner class RespecFailure {
        @Test
        fun `respec fails without enough gold`() = runTest {
            loginAndSetup(gold = 500, level = 10)
            learnAbilities()

            outbound.drainAll()
            router.handle(SID, Command.Train.Reset)

            val events = outbound.drainAll()
            val errors = events.errorMessages(SID)
            assertTrue(errors.any { it.contains("need 1000 gold") }, "Expected gold error, got: $errors")

            // Abilities should remain
            val me = players.get(SID)!!
            assertEquals(2, me.learnedAbilityIds.size)
        }

        @Test
        fun `respec fails without trainer in room`() = runTest {
            loginAndSetup(gold = 2000, level = 10)
            learnAbilities()

            // Move player to a room without a trainer
            val me = players.get(SID)!!
            me.roomId = NO_TRAINER_ROOM

            outbound.drainAll()
            router.handle(SID, Command.Train.Reset)

            val events = outbound.drainAll()
            val errors = events.errorMessages(SID)
            assertTrue(errors.any { it.contains("no trainer here") }, "Expected no-trainer error, got: $errors")

            // Abilities should remain
            assertEquals(2, me.learnedAbilityIds.size)
        }

        @Test
        fun `respec fails when no abilities learned`() = runTest {
            loginAndSetup(gold = 2000, level = 10)
            abilitySystem.loadAbilities(SID, emptySet())

            outbound.drainAll()
            router.handle(SID, Command.Train.Reset)

            val events = outbound.drainAll()
            val errors = events.errorMessages(SID)
            assertTrue(
                errors.any { it.contains("no learned abilities") },
                "Expected no-abilities error, got: $errors",
            )
        }

        @Test
        fun `cooldown prevents rapid respec`() = runTest {
            loginAndSetup(gold = 5000, level = 10)
            learnAbilities()

            // First respec succeeds
            clock.set(1_000_000)
            outbound.drainAll()
            router.handle(SID, Command.Train.Reset)
            val texts1 = outbound.drainAll().textMessages(SID)
            assertTrue(texts1.any { it.contains("abilities have been reset") })

            // Re-learn an ability so there's something to reset
            val me = players.get(SID)!!
            abilitySystem.learnAbility(
                sessionId = SID,
                abilityId = AbilityId("fireball"),
                level = me.level,
                unlockedClasses = me.unlockedClasses,
                skillPointInterval = skillPointsConfig.interval,
            )
            me.learnedAbilityIds.add("fireball")

            // Second respec too soon should fail
            clock.advance(600_000) // 10 minutes (less than 1 hour cooldown)
            outbound.drainAll()
            router.handle(SID, Command.Train.Reset)

            val events2 = outbound.drainAll()
            val errors = events2.errorMessages(SID)
            assertTrue(
                errors.any { it.contains("wait") && it.contains("seconds") },
                "Expected cooldown error, got: $errors",
            )
            assertEquals(1, me.learnedAbilityIds.size, "Abilities should not be cleared during cooldown")
        }

        @Test
        fun `cooldown allows respec after period expires`() = runTest {
            loginAndSetup(gold = 5000, level = 10)
            learnAbilities()

            // First respec
            clock.set(1_000_000)
            outbound.drainAll()
            router.handle(SID, Command.Train.Reset)
            outbound.drainAll()

            // Re-learn an ability
            val me = players.get(SID)!!
            abilitySystem.learnAbility(
                sessionId = SID,
                abilityId = AbilityId("fireball"),
                level = me.level,
                unlockedClasses = me.unlockedClasses,
                skillPointInterval = skillPointsConfig.interval,
            )
            me.learnedAbilityIds.add("fireball")

            // After cooldown period passes
            clock.advance(3_600_001) // 1 hour + 1 ms
            outbound.drainAll()
            router.handle(SID, Command.Train.Reset)

            val events = outbound.drainAll()
            val texts = events.textMessages(SID)
            assertTrue(
                texts.any { it.contains("abilities have been reset") },
                "Expected successful respec after cooldown, got: $texts",
            )
        }

        @Test
        fun `respec fails when disabled`() = runTest {
            // Rebuild handler with respec disabled
            val disabledConfig = RespecConfig(enabled = false)
            val world = World(
                rooms = mapOf(
                    TRAINER_ROOM to Room(
                        id = TRAINER_ROOM,
                        title = "Training Room",
                        description = "A training room.",
                        exits = emptyMap(),
                    ),
                ),
                startRoom = TRAINER_ROOM,
            )
            val mobs = MobRegistry()
            val combat = CombatSystem(players, mobs, items, outbound)
            val trainerRegistry = TrainerRegistry()
            trainerRegistry.register(
                listOf(
                    TrainerDefinition(
                        id = "mage_trainer",
                        name = "Archmage Zara",
                        classNames = listOf("MAGE"),
                        roomId = TRAINER_ROOM,
                    ),
                ),
            )
            val ctx = EngineContext(
                players = players,
                mobs = mobs,
                world = world,
                items = items,
                outbound = outbound,
                combat = combat,
                gmcpEmitter = null,
                worldState = null,
            )
            val disabledRouter = CommandRouter(outbound = outbound, players = players)
            TrainerHandler(
                ctx = ctx,
                abilitySystem = abilitySystem,
                trainerRegistry = trainerRegistry,
                skillPointsConfig = skillPointsConfig,
                respecConfig = disabledConfig,
                clock = clock,
            ).register(disabledRouter)

            loginAndSetup(gold = 2000, level = 10)
            learnAbilities()
            outbound.drainAll()
            disabledRouter.handle(SID, Command.Train.Reset)

            val events = outbound.drainAll()
            val errors = events.errorMessages(SID)
            assertTrue(
                errors.any { it.contains("not available") },
                "Expected disabled error, got: $errors",
            )
        }

        @Test
        fun `staff can respec for free`() = runTest {
            loginAndSetup(gold = 0, level = 10)
            learnAbilities()

            val me = players.get(SID)!!
            me.isStaff = true

            outbound.drainAll()
            router.handle(SID, Command.Train.Reset)

            val events = outbound.drainAll()
            val texts = events.textMessages(SID)
            assertTrue(
                texts.any { it.contains("abilities have been reset") },
                "Expected successful respec for staff, got: $texts",
            )
            assertEquals(0L, me.gold, "Staff gold should remain unchanged at 0")
        }
    }

    /**
     * Level-gated abilities should no longer silently produce a confusing "no matching
     * ability" error â€” they should surface the specific level requirement. See #1036.
     */
    @Nested
    inner class LearnLockGating {
        @Test
        fun `train learn level-locked ability returns clear requires-level error`() = runTest {
            loginAndSetup(gold = 2000, level = 1)
            abilitySystem.loadAbilities(SID, emptySet())
            outbound.drainAll()

            // fireball requires level 2 in the fixture â€” player is level 1
            router.handle(SID, Command.Train.Learn("fireball"))

            val events = outbound.drainAll()
            val errors = events.errorMessages(SID)
            assertTrue(
                errors.any { it.contains("level 2") && it.contains("Fireball") },
                "Expected a clear level-requirement error referencing 'level 2' and 'Fireball', got: $errors",
            )

            // And the player should not have learned it
            val me = players.get(SID)!!
            assertTrue(
                "fireball" !in me.learnedAbilityIds,
                "Level-locked ability should not be learned; learned ids: ${me.learnedAbilityIds}",
            )
        }

        @Test
        fun `train learn unknown ability still returns no-matching error`() = runTest {
            loginAndSetup(gold = 2000, level = 10)
            abilitySystem.loadAbilities(SID, emptySet())
            outbound.drainAll()

            router.handle(SID, Command.Train.Learn("nonexistent"))

            val events = outbound.drainAll()
            val errors = events.errorMessages(SID)
            assertTrue(
                errors.any { it.contains("No trainable ability matching") },
                "Expected no-matching-ability error, got: $errors",
            )
        }
    }
}
