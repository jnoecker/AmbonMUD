package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.MobVariantsConfig
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.mob.MobRole
import dev.ambon.domain.world.MobSpawn
import dev.ambon.domain.world.MobTemplateDef
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.SpawnCondition
import dev.ambon.domain.world.World
import dev.ambon.engine.behavior.BehaviorTreeSystem
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConditionalSpawnHandlerTest {
    private val roomId = RoomId("z:a")
    private val owlId = MobId("z:owl")

    /** Mutable world conditions the handler's lambdas read each tick. */
    private class Conditions(
        var period: TimePeriod = TimePeriod.DAY,
        var weather: String = "CLEAR",
        var inCombat: Boolean = false,
    )

    private class Harness(
        val handler: ConditionalSpawnHandler,
        val mobs: MobRegistry,
        val clock: MutableClock,
        val conditions: Conditions,
    )

    private fun buildHarness(
        condition: SpawnCondition,
        startPeriod: TimePeriod = TimePeriod.DAY,
    ): Harness {
        val clock = MutableClock(0L)
        val outbound = LocalOutboundBus()
        val mobs = MobRegistry()
        val items = ItemRegistry()
        val template = MobTemplateDef(
            id = owlId,
            name = "snowy owl",
            role = MobRole.COMBAT,
            spawnCondition = condition,
        )
        val world = World(
            rooms = mapOf(roomId to Room(roomId, "A", "desc", emptyMap())),
            startRoom = roomId,
            mobTemplates = mapOf(owlId to template),
            mobSpawns = listOf(MobSpawn(id = owlId, templateId = owlId, roomId = roomId)),
        )
        val players = PlayerRegistry(
            startRoom = world.startRoom,
            repo = InMemoryPlayerRepository(),
            items = items,
            clock = clock,
        )
        val behaviorTreeSystem = BehaviorTreeSystem(world, mobs, players, outbound, clock)
        val mobSystem = MobSystem()
        val combatSystem = CombatSystem(players, mobs, items, outbound, clock)
        val dialogueSystem = DialogueSystem(mobs, players, outbound)
        val statusEffectSystem = StatusEffectSystem(StatusEffectRegistry(), players, mobs, outbound, clock)
        val gmcpEmitter = GmcpEmitter(outbound = outbound, supportsPackage = { _, _ -> false })
        val mobRemovalCoordinator = MobRemovalCoordinator(
            combatSystem = combatSystem,
            dialogueSystem = dialogueSystem,
            behaviorTreeSystem = behaviorTreeSystem,
            mobs = mobs,
            mobSystem = mobSystem,
            statusEffectSystem = statusEffectSystem,
        )
        val conditions = Conditions(period = startPeriod)
        val handler = ConditionalSpawnHandler(
            world = world,
            mobs = mobs,
            players = players,
            outbound = outbound,
            gmcpEmitter = gmcpEmitter,
            mobSystem = mobSystem,
            behaviorTreeSystem = behaviorTreeSystem,
            mobRemovalCoordinator = mobRemovalCoordinator,
            variantRoller = MobVariantRoller(MobVariantsConfig(enabled = false)),
            period = { conditions.period },
            season = { Season.WINTER },
            weatherForZone = { conditions.weather },
            activeEventFlags = { emptySet() },
            isMobInCombat = { conditions.inCombat },
            clock = clock,
            checkIntervalMs = 1_000L,
        )
        return Harness(handler, mobs, clock, conditions)
    }

    @Test
    fun `mob is absent at start when gated`() {
        val h = buildHarness(SpawnCondition(timePeriods = setOf(TimePeriod.NIGHT)))
        assertNull(h.mobs.get(owlId))
    }

    @Test
    fun `mob appears when gates open and fades when they close`() = runTest {
        val h = buildHarness(SpawnCondition(timePeriods = setOf(TimePeriod.NIGHT)))
        // Day: still absent after a check.
        h.handler.tick()
        assertNull(h.mobs.get(owlId))

        // Night: appears on the next check.
        h.conditions.period = TimePeriod.NIGHT
        h.clock.advance(1_000L)
        h.handler.tick()
        assertNotNull(h.mobs.get(owlId))

        // Back to day: fades out.
        h.conditions.period = TimePeriod.DAY
        h.clock.advance(1_000L)
        h.handler.tick()
        assertNull(h.mobs.get(owlId))
    }

    @Test
    fun `mob mid-combat is not yanked when gates close`() = runTest {
        val h = buildHarness(SpawnCondition(timePeriods = setOf(TimePeriod.NIGHT)), startPeriod = TimePeriod.NIGHT)
        h.handler.tick()
        assertNotNull(h.mobs.get(owlId))

        h.conditions.inCombat = true
        h.conditions.period = TimePeriod.DAY
        h.clock.advance(1_000L)
        h.handler.tick()
        assertNotNull(h.mobs.get(owlId))
    }

    @Test
    fun `chance zero never spawns even when gates are open`() = runTest {
        val h = buildHarness(
            SpawnCondition(timePeriods = setOf(TimePeriod.NIGHT), chance = 0.0),
            startPeriod = TimePeriod.NIGHT,
        )
        h.handler.tick()
        assertNull(h.mobs.get(owlId))
    }
}
