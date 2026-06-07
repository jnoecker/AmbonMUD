package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.engine.behavior.BehaviorTreeSystem
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.drainAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Zone resets must not yank a mob out from under a player mid-fight (#1222).
 * Mobs in combat survive the reset; their spawn slot repopulates later via the
 * normal post-death respawn timer or a subsequent reset once combat has ended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZoneResetCombatExemptionTest {
    private val world = dev.ambon.test.TestWorlds.okSmall
    private val ratId = MobId("ok_small:rat")
    private val ratRoom = RoomId("ok_small:b")

    private data class Harness(
        val players: PlayerRegistry,
        val mobs: MobRegistry,
        val items: ItemRegistry,
        val combat: CombatSystem,
        val outbound: LocalOutboundBus,
        val handler: ZoneResetHandler,
        val clock: MutableClock,
    )

    private fun buildHarness(): Harness {
        val clock = MutableClock(0L)
        val outbound = LocalOutboundBus()
        val mobs = MobRegistry()
        val items = ItemRegistry()
        items.loadSpawns(world.itemSpawns)
        val players = PlayerRegistry(
            startRoom = world.startRoom,
            repo = InMemoryPlayerRepository(),
            items = items,
            clock = clock,
        )
        val behaviorTreeSystem = BehaviorTreeSystem(
            world = world,
            mobs = mobs,
            players = players,
            outbound = outbound,
            clock = clock,
        )
        val mobSystem = MobSystem()
        val combatSystem = CombatSystem(
            players = players,
            mobs = mobs,
            items = items,
            outbound = outbound,
            clock = clock,
        )
        val dialogueSystem = DialogueSystem(
            mobs = mobs,
            players = players,
            outbound = outbound,
        )
        val statusEffectSystem = StatusEffectSystem(
            registry = StatusEffectRegistry(),
            players = players,
            mobs = mobs,
            outbound = outbound,
            clock = clock,
        )
        val worldState = WorldStateRegistry(world)
        val gmcpEmitter = GmcpEmitter(
            outbound = outbound,
            supportsPackage = { _, _ -> false },
        )
        val mobRemovalCoordinator = MobRemovalCoordinator(
            combatSystem = combatSystem,
            dialogueSystem = dialogueSystem,
            behaviorTreeSystem = behaviorTreeSystem,
            mobs = mobs,
            mobSystem = mobSystem,
            statusEffectSystem = statusEffectSystem,
        )
        val handler = ZoneResetHandler(
            world = world,
            mobs = mobs,
            items = items,
            players = players,
            outbound = outbound,
            worldState = worldState,
            mobRemovalCoordinator = mobRemovalCoordinator,
            mobSystem = mobSystem,
            behaviorTreeSystem = behaviorTreeSystem,
            gmcpEmitter = gmcpEmitter,
            clock = clock,
            isMobInCombat = { mobId -> combatSystem.isMobInCombat(mobId) },
        )
        // Populate the world's initial mob spawns (GameEngine does this at boot).
        for (spawn in world.mobSpawns) {
            mobs.upsert(spawnToMobState(spawn, world))
        }
        return Harness(players, mobs, items, combatSystem, outbound, handler, clock)
    }

    /** Advances past the ok_small 1-minute lifespan and fires the reset tick. */
    private suspend fun Harness.advancePastResetAndTick() {
        clock.advance(61_000L)
        handler.tick()
    }

    @Test
    fun `zone reset replaces idle mobs with fresh copies`() = runTest {
        val h = buildHarness()
        val rat = h.mobs.get(ratId)!!
        rat.hp = 1

        h.advancePastResetAndTick()

        val replaced = h.mobs.get(ratId)
        assertNotNull(replaced, "rat should be repopulated by the reset")
        assertEquals(replaced!!.maxHp, replaced.hp, "idle rat should be replaced by a fresh full-HP copy")
    }

    @Test
    fun `zone reset spares a mob that is fighting a player`() = runTest {
        val h = buildHarness()
        val sid = dev.ambon.domain.ids.SessionId(1)
        require(h.players.login(sid, "Fighter", "password") == LoginResult.Ok)
        h.players.moveTo(sid, ratRoom)

        assertNull(h.combat.startCombat(sid, "rat"))
        val rat = h.mobs.get(ratId)!!
        rat.hp = 5
        h.outbound.drainAll()

        h.advancePastResetAndTick()

        // The fight is untouched: same mob instance, same damage, combat intact.
        assertSame(rat, h.mobs.get(ratId), "in-combat rat must not be replaced by the reset")
        assertEquals(5, rat.hp, "in-combat rat must keep its current HP through the reset")
        assertTrue(h.combat.isInCombat(sid), "player must still be in combat after the reset")
        assertEquals(ratId, h.combat.currentTarget(sid))

        val texts = h.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
        assertFalse(
            texts.any { it == "Your opponent vanishes." },
            "no vanish message should be sent for an in-combat mob, got: $texts",
        )
        assertTrue(
            texts.any { it == "The air shimmers as the area resets around you." },
            "the reset notice itself should still reach players in the zone, got: $texts",
        )
    }

    @Test
    fun `a later reset replaces the mob once combat has ended`() = runTest {
        val h = buildHarness()
        val sid = dev.ambon.domain.ids.SessionId(1)
        require(h.players.login(sid, "Fighter", "password") == LoginResult.Ok)
        h.players.moveTo(sid, ratRoom)

        assertNull(h.combat.startCombat(sid, "rat"))
        h.mobs.get(ratId)!!.hp = 5

        // First reset: rat survives because it is fighting.
        h.advancePastResetAndTick()
        assertEquals(5, h.mobs.get(ratId)!!.hp)

        // Combat ends (player flees); the next reset reclaims the slot.
        assertNull(h.combat.flee(sid))
        h.advancePastResetAndTick()

        val replaced = h.mobs.get(ratId)
        assertNotNull(replaced, "rat slot should repopulate on the next reset after combat ends")
        assertEquals(replaced!!.maxHp, replaced.hp, "post-combat reset should spawn a fresh rat")
    }
}
