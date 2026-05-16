package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.MulticlassConfig
import dev.ambon.config.RespecConfig
import dev.ambon.config.SkillPointsConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.TrainerDefinition
import dev.ambon.domain.world.World
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.TrainerRegistry
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
import dev.ambon.test.infoMessages
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrainerUnlockEconomicsTest {
    @Nested
    inner class CostFor {
        @Test
        fun `flat cost when multiplier is 1`() {
            val cfg = MulticlassConfig(goldCost = 500, goldCostMultiplier = 1.0)
            // Starter only: first trainer unlock pays the base.
            assertEquals(500L, cfg.costFor(currentlyUnlocked = 1))
            assertEquals(500L, cfg.costFor(currentlyUnlocked = 5))
        }

        @Test
        fun `exponential scaling kicks in after the first trainer unlock`() {
            val cfg = MulticlassConfig(goldCost = 500, goldCostMultiplier = 2.0)
            assertEquals(500L, cfg.costFor(currentlyUnlocked = 1)) // 500 * 2^0
            assertEquals(1000L, cfg.costFor(currentlyUnlocked = 2)) // 500 * 2^1
            assertEquals(2000L, cfg.costFor(currentlyUnlocked = 3)) // 500 * 2^2
            assertEquals(4000L, cfg.costFor(currentlyUnlocked = 4)) // 500 * 2^3
        }

        @Test
        fun `non-integer multiplier rounds toward zero`() {
            val cfg = MulticlassConfig(goldCost = 100, goldCostMultiplier = 1.5)
            assertEquals(100L, cfg.costFor(currentlyUnlocked = 1))
            assertEquals(150L, cfg.costFor(currentlyUnlocked = 2))
            assertEquals(225L, cfg.costFor(currentlyUnlocked = 3))
        }

        @Test
        fun `zero or negative current count is treated as the first unlock`() {
            val cfg = MulticlassConfig(goldCost = 500, goldCostMultiplier = 2.0)
            assertEquals(500L, cfg.costFor(currentlyUnlocked = 0))
            assertEquals(500L, cfg.costFor(currentlyUnlocked = -3))
        }

        @Test
        fun `validation rejects bad inputs`() {
            assertThrows(IllegalArgumentException::class.java) { MulticlassConfig(maxClasses = 0) }
            assertThrows(IllegalArgumentException::class.java) { MulticlassConfig(goldCost = -1) }
            assertThrows(IllegalArgumentException::class.java) { MulticlassConfig(minLevel = 0) }
            assertThrows(IllegalArgumentException::class.java) { MulticlassConfig(goldCostMultiplier = 0.5) }
        }
    }

    // ── Router-level integration ─────────────────────────────────────────

    companion object {
        private val TRAINER_ROOM = RoomId("test:trainer")
        private val SID = SessionId(1L)
    }

    private lateinit var clock: MutableClock
    private lateinit var outbound: LocalOutboundBus
    private lateinit var repo: InMemoryPlayerRepository
    private lateinit var items: ItemRegistry
    private lateinit var router: CommandRouter
    private lateinit var players: dev.ambon.engine.PlayerRegistry

    private val skillPointsConfig = SkillPointsConfig(interval = 2)
    private val respecConfig = RespecConfig(enabled = true, goldCost = 1000, cooldownMs = 0)

    private fun buildWorld(): World = World(
        rooms = mapOf(
            TRAINER_ROOM to Room(
                id = TRAINER_ROOM,
                title = "Hall of Mentors",
                description = "Trainers of every stripe gather here.",
                exits = emptyMap(),
            ),
        ),
        startRoom = TRAINER_ROOM,
    )

    private fun setUpHandler(multiclassConfig: MulticlassConfig) {
        clock = MutableClock(0L)
        outbound = LocalOutboundBus()
        repo = InMemoryPlayerRepository()
        items = ItemRegistry()
        val world = buildWorld()
        players = buildTestPlayerRegistry(
            startRoom = TRAINER_ROOM,
            repo = repo,
            items = items,
            clock = clock,
        )
        val mobs = MobRegistry()
        val combat = CombatSystem(players, mobs, items, outbound)
        val abilityRegistry = AbilityRegistry()
        val abilitySystem = AbilitySystem(
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
                    id = "polyglot_trainer",
                    name = "Master Polyglot",
                    classNames = listOf("MAGE", "ROGUE", "WARRIOR"),
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
        TrainerHandler(
            ctx = ctx,
            abilitySystem = abilitySystem,
            trainerRegistry = trainerRegistry,
            skillPointsConfig = skillPointsConfig,
            multiclassConfig = multiclassConfig,
            respecConfig = respecConfig,
            clock = clock,
        ).register(router)
    }

    private suspend fun loginAt(level: Int, gold: Long, starterClass: String = "MAGE") {
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
        me.level = level
        me.gold = gold
        me.playerClass = starterClass
        me.unlockedClasses.clear()
        me.unlockedClasses.add(starterClass)
    }

    @BeforeEach
    fun setUp() {
        // Each test calls setUpHandler() with its own multiclass config.
    }

    @Test
    fun `gold cost scales exponentially across successive unlocks`() = runTest {
        setUpHandler(MulticlassConfig(minLevel = 1, goldCost = 500, maxClasses = 5, goldCostMultiplier = 2.0))
        loginAt(level = 20, gold = 5000)
        val me = players.get(SID)!!

        // 1st trainer unlock: starter MAGE → +ROGUE, costs 500 (base).
        router.handle(SID, Command.Train.Unlock(className = "rogue"))
        assertEquals(setOf("MAGE", "ROGUE"), me.unlockedClasses)
        assertEquals(4500L, me.gold)

        // 2nd trainer unlock: +WARRIOR, costs 500 * 2^1 = 1000.
        router.handle(SID, Command.Train.Unlock(className = "warrior"))
        assertEquals(setOf("MAGE", "ROGUE", "WARRIOR"), me.unlockedClasses)
        assertEquals(3500L, me.gold)
    }

    @Test
    fun `unlock fails when player would exceed maxClasses`() = runTest {
        setUpHandler(MulticlassConfig(minLevel = 1, goldCost = 100, maxClasses = 2, goldCostMultiplier = 1.0))
        loginAt(level = 20, gold = 5000)
        val me = players.get(SID)!!

        router.handle(SID, Command.Train.Unlock(className = "rogue"))
        assertEquals(2, me.unlockedClasses.size)
        outbound.drainAll()

        router.handle(SID, Command.Train.Unlock(className = "warrior"))
        val errs = outbound.drainAll().errorMessages(SID)
        assertTrue(
            errs.any { it.contains("maximum of 2 classes") },
            "Expected max-classes error, got: $errs",
        )
        assertFalse("WARRIOR" in me.unlockedClasses)
        assertEquals(4900L, me.gold) // unchanged after failed unlock
    }

    @Test
    fun `train list shows scaled cost for the next unlock`() = runTest {
        setUpHandler(MulticlassConfig(minLevel = 1, goldCost = 500, maxClasses = 5, goldCostMultiplier = 2.0))
        loginAt(level = 20, gold = 5000)
        // Already unlocked one class via trainer → next cost should be 500 * 2 = 1000.
        players.get(SID)!!.unlockedClasses.add("ROGUE")
        outbound.drainAll()

        router.handle(SID, Command.Train.List)
        val texts = outbound.drainAll().infoMessages(SID)
        assertTrue(
            texts.any { it.contains("pay 1000 gold") },
            "Expected scaled cost in lock hint, got: $texts",
        )
    }

    @Test
    fun `train list reports limit reached instead of cost when at max`() = runTest {
        setUpHandler(MulticlassConfig(minLevel = 1, goldCost = 500, maxClasses = 2, goldCostMultiplier = 2.0))
        loginAt(level = 20, gold = 5000)
        players.get(SID)!!.unlockedClasses.add("ROGUE") // now at max=2
        outbound.drainAll()

        router.handle(SID, Command.Train.List)
        val texts = outbound.drainAll().infoMessages(SID)
        assertTrue(
            texts.any { it.contains("limit of 2 unlocked classes") },
            "Expected limit-reached hint, got: $texts",
        )
        assertFalse(
            texts.any { it.contains("pay") && it.contains("gold") },
            "Should not show pay-gold hint when at limit, got: $texts",
        )
    }
}
