package dev.ambon.engine.abilities

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.EffectType
import dev.ambon.engine.status.StackBehavior
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class AbilitySystemTest {
    private val roomId = RoomId("zone:room")
    private val sid = SessionId(1L)

    private fun buildSystem(
        clock: MutableClock = MutableClock(0L),
        rng: Random = Random(42),
    ): TestHarness {
        val items = ItemRegistry()
        val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val combat =
            CombatSystem(
                players = players,
                mobs = mobs,
                items = items,
                outbound = outbound,
                clock = clock,
                rng = rng,
                tickMillis = 1_000L,
            )
        val registry = AbilityRegistry()
        registry.register(
            AbilityDefinition(
                id = AbilityId("magic_missile"),
                displayName = "Magic Missile",
                description = "A bolt of arcane energy.",
                manaCost = 8,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = TargetType.ENEMY,
                effect = AbilityEffect.DirectDamage(minDamage = 5, maxDamage = 5),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("heal"),
                displayName = "Heal",
                description = "Restore HP.",
                manaCost = 10,
                cooldownMs = 5000,
                levelRequired = 1,
                targetType = TargetType.SELF,
                effect = AbilityEffect.DirectHeal(minHeal = 5, maxHeal = 5),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("fireball"),
                displayName = "Fireball",
                description = "Fire!",
                manaCost = 15,
                cooldownMs = 3000,
                levelRequired = 5,
                targetType = TargetType.ENEMY,
                effect = AbilityEffect.DirectDamage(minDamage = 10, maxDamage = 10),
            ),
        )
        val abilitySystem =
            AbilitySystem(
                players = players,
                registry = registry,
                outbound = outbound,
                combat = combat,
                clock = clock,
                rng = rng,
            )
        return TestHarness(
            players = players,
            mobs = mobs,
            items = items,
            outbound = outbound,
            combat = combat,
            registry = registry,
            abilitySystem = abilitySystem,
            clock = clock,
        )
    }

    private data class TestHarness(
        val players: PlayerRegistry,
        val mobs: MobRegistry,
        val items: ItemRegistry,
        val outbound: LocalOutboundBus,
        val combat: CombatSystem,
        val registry: AbilityRegistry,
        val abilitySystem: AbilitySystem,
        val clock: MutableClock,
    )

    private suspend fun login(
        players: PlayerRegistry,
        sessionId: SessionId,
        name: String,
    ) {
        val res = players.login(sessionId, name, "password")
        require(res == LoginResult.Ok) { "Login failed: $res" }
    }

    private fun drainOutbound(outbound: LocalOutboundBus): List<OutboundEvent> {
        val events = mutableListOf<OutboundEvent>()
        while (true) {
            val event = outbound.tryReceive().getOrNull() ?: break
            events.add(event)
        }
        return events
    }

    @Test
    fun `cast damage spell reduces mob hp and deducts mana`() =
        runTest {
            val h = buildSystem()
            login(h.players, sid, "Caster")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 20

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)
            drainOutbound(h.outbound)

            val err = h.abilitySystem.cast(sid, "magic_missile", "rat")
            assertNull(err)

            assertEquals(12, player.mana)
            assertEquals(15, mob.hp)

            val messages =
                drainOutbound(h.outbound)
                    .filterIsInstance<OutboundEvent.SendText>()
                    .map { it.text }
            assertTrue(messages.any { it.contains("Magic Missile hits a rat for 5 damage") })
        }

    @Test
    fun `cast heal restores hp and deducts mana`() =
        runTest {
            val h = buildSystem()
            login(h.players, sid, "Healer")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 20
            player.hp = 5
            drainOutbound(h.outbound)

            val err = h.abilitySystem.cast(sid, "heal", null)
            assertNull(err)

            assertEquals(10, player.mana)
            assertEquals(10, player.hp)

            val messages =
                drainOutbound(h.outbound)
                    .filterIsInstance<OutboundEvent.SendText>()
                    .map { it.text }
            assertTrue(messages.any { it.contains("Heal heals you for 5 HP") })
        }

    @Test
    fun `cast fails with insufficient mana`() =
        runTest {
            val h = buildSystem()
            login(h.players, sid, "LowMana")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 5

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)

            val err = h.abilitySystem.cast(sid, "magic_missile", "rat")
            assertNotNull(err)
            assertTrue(err!!.contains("Not enough mana"))
            assertEquals(5, player.mana)
        }

    @Test
    fun `cast fails when spell on cooldown`() =
        runTest {
            val h = buildSystem()
            login(h.players, sid, "Cooldown")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 50
            player.hp = 5
            drainOutbound(h.outbound)

            // First cast should work
            val err1 = h.abilitySystem.cast(sid, "heal", null)
            assertNull(err1)

            // Second cast immediately should fail (5s cooldown)
            val err2 = h.abilitySystem.cast(sid, "heal", null)
            assertNotNull(err2)
            assertTrue(err2!!.contains("cooldown"))

            // Advance past cooldown
            h.clock.advance(6_000L)
            val player2 = h.players.get(sid)!!
            player2.hp = 5
            val err3 = h.abilitySystem.cast(sid, "heal", null)
            assertNull(err3)
        }

    @Test
    fun `unknown spell returns error`() =
        runTest {
            val h = buildSystem()
            login(h.players, sid, "Unknown")
            h.abilitySystem.syncAbilities(sid, 1)

            val err = h.abilitySystem.cast(sid, "thunderbolt", "rat")
            assertNotNull(err)
            assertTrue(err!!.contains("don't know"))
        }

    @Test
    fun `level-gated spell not available at low level`() =
        runTest {
            val h = buildSystem()
            login(h.players, sid, "LowLevel")
            h.abilitySystem.syncAbilities(sid, 1)

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)

            // fireball requires level 5
            val err = h.abilitySystem.cast(sid, "fireball", "rat")
            assertNotNull(err)
            assertTrue(err!!.contains("don't know"))
        }

    @Test
    fun `level-gated spell available at correct level`() =
        runTest {
            val h = buildSystem()
            login(h.players, sid, "HighLevel")
            h.abilitySystem.syncAbilities(sid, 5)
            val player = h.players.get(sid)!!
            player.mana = 30

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)

            val err = h.abilitySystem.cast(sid, "fireball", "rat")
            assertNull(err)
            assertEquals(15, player.mana)
            assertEquals(10, mob.hp)
        }

    @Test
    fun `spell kills mob triggers handleSpellKill`() =
        runTest {
            val h = buildSystem()
            login(h.players, sid, "Killer")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 20

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 3, maxHp = 20)
            h.mobs.upsert(mob)
            drainOutbound(h.outbound)

            val err = h.abilitySystem.cast(sid, "magic_missile", "rat")
            assertNull(err)

            // Mob should be dead
            assertNull(h.mobs.get(MobId("zone:rat")))
            val messages =
                drainOutbound(h.outbound)
                    .filterIsInstance<OutboundEvent.SendText>()
                    .map { it.text }
            assertTrue(messages.any { it.contains("dies") })
        }

    @Test
    fun `knownAbilities reflects level`() =
        runTest {
            val h = buildSystem()
            login(h.players, sid, "Lister")
            h.abilitySystem.syncAbilities(sid, 1)

            val level1 = h.abilitySystem.knownAbilities(sid)
            assertEquals(2, level1.size) // magic_missile and heal

            h.abilitySystem.syncAbilities(sid, 5)
            val level5 = h.abilitySystem.knownAbilities(sid)
            assertEquals(3, level5.size) // + fireball
        }

    @Test
    fun `cast enemy spell without target and not in combat returns error`() =
        runTest {
            val h = buildSystem()
            login(h.players, sid, "NoTarget")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 20

            val err = h.abilitySystem.cast(sid, "magic_missile", null)
            assertNotNull(err)
            assertTrue(err!!.contains("on whom"))
        }

    @Test
    fun `session cleanup removes learned abilities and cooldowns`() =
        runTest {
            val h = buildSystem()
            login(h.players, sid, "Cleanup")
            h.abilitySystem.syncAbilities(sid, 1)

            assertTrue(h.abilitySystem.knownAbilities(sid).isNotEmpty())
            h.abilitySystem.onPlayerDisconnected(sid)
            assertTrue(h.abilitySystem.knownAbilities(sid).isEmpty())
        }

    @Test
    fun `cast APPLY_STATUS on enemy mob applies effect`() =
        runTest {
            val h = buildSystemWithStatusEffects()
            login(h.players, sid, "Caster")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 30

            val mob = MobState(MobId("zone:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            h.mobs.upsert(mob)
            drainOutbound(h.outbound)

            val err = h.abilitySystem.cast(sid, "ignite", "rat")
            assertNull(err)

            assertTrue(player.mana < 30, "Mana should be deducted")
            assertTrue(
                h.statusEffects.hasMobEffect(mob.id, EffectType.DOT),
                "DOT should be applied to mob",
            )
        }

    @Test
    fun `cast APPLY_STATUS on self applies effect to player`() =
        runTest {
            val h = buildSystemWithStatusEffects()
            login(h.players, sid, "Buffer")
            h.abilitySystem.syncAbilities(sid, 1)
            val player = h.players.get(sid)!!
            player.mana = 30
            drainOutbound(h.outbound)

            val err = h.abilitySystem.cast(sid, "shield", null)
            assertNull(err)

            assertTrue(player.mana < 30, "Mana should be deducted")
            assertTrue(
                h.statusEffects.hasPlayerEffect(sid, EffectType.SHIELD),
                "SHIELD should be applied to player",
            )
        }

    private fun buildSystemWithStatusEffects(
        clock: MutableClock = MutableClock(0L),
        rng: Random = Random(42),
    ): StatusTestHarness {
        val items = ItemRegistry()
        val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val combat =
            CombatSystem(
                players = players,
                mobs = mobs,
                items = items,
                outbound = outbound,
                clock = clock,
                rng = rng,
                tickMillis = 1_000L,
            )
        val statusRegistry = StatusEffectRegistry()
        statusRegistry.register(
            StatusEffectDefinition(
                id = StatusEffectId("ignite"),
                displayName = "Ignite",
                effectType = EffectType.DOT,
                durationMs = 6000,
                tickIntervalMs = 2000,
                tickMinValue = 5,
                tickMaxValue = 5,
            ),
        )
        statusRegistry.register(
            StatusEffectDefinition(
                id = StatusEffectId("shield"),
                displayName = "Shield",
                effectType = EffectType.SHIELD,
                durationMs = 30000,
                shieldAmount = 20,
                stackBehavior = StackBehavior.NONE,
            ),
        )
        val statusEffects =
            StatusEffectSystem(
                registry = statusRegistry,
                players = players,
                mobs = mobs,
                outbound = outbound,
                clock = clock,
                rng = rng,
                markVitalsDirty = {},
                markMobHpDirty = {},
                markStatusDirty = {},
            )
        val registry = AbilityRegistry()
        registry.register(
            AbilityDefinition(
                id = AbilityId("ignite"),
                displayName = "Ignite",
                description = "Burns the target.",
                manaCost = 12,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = TargetType.ENEMY,
                effect = AbilityEffect.ApplyStatus(StatusEffectId("ignite")),
            ),
        )
        registry.register(
            AbilityDefinition(
                id = AbilityId("shield"),
                displayName = "Shield",
                description = "Grants a shield.",
                manaCost = 15,
                cooldownMs = 0,
                levelRequired = 1,
                targetType = TargetType.SELF,
                effect = AbilityEffect.ApplyStatus(StatusEffectId("shield")),
            ),
        )
        val abilitySystem =
            AbilitySystem(
                players = players,
                registry = registry,
                outbound = outbound,
                combat = combat,
                clock = clock,
                rng = rng,
                statusEffects = statusEffects,
            )
        return StatusTestHarness(
            players = players,
            mobs = mobs,
            outbound = outbound,
            abilitySystem = abilitySystem,
            statusEffects = statusEffects,
            clock = clock,
        )
    }

    private data class StatusTestHarness(
        val players: PlayerRegistry,
        val mobs: MobRegistry,
        val outbound: LocalOutboundBus,
        val abilitySystem: AbilitySystem,
        val statusEffects: StatusEffectSystem,
        val clock: MutableClock,
    )
}
