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

    private fun buildRegistry(): AbilityRegistry {
        val reg = AbilityRegistry()
        reg.register(
            AbilityDefinition(
                id = AbilityId("magic_missile"),
                displayName = "Magic Missile",
                description = "Hurls a bolt.",
                manaCost = 5,
                cooldownMs = 0L,
                levelRequired = 1,
                targetType = TargetType.ENEMY,
                effect = AbilityEffect.DirectDamage(3, 6),
            ),
        )
        reg.register(
            AbilityDefinition(
                id = AbilityId("heal"),
                displayName = "Heal",
                description = "Restores health.",
                manaCost = 8,
                cooldownMs = 3000L,
                levelRequired = 1,
                targetType = TargetType.SELF,
                effect = AbilityEffect.DirectHeal(4, 8),
            ),
        )
        reg.register(
            AbilityDefinition(
                id = AbilityId("fireball"),
                displayName = "Fireball",
                description = "Engulfs in flames.",
                manaCost = 12,
                cooldownMs = 5000L,
                levelRequired = 5,
                targetType = TargetType.ENEMY,
                effect = AbilityEffect.DirectDamage(6, 12),
            ),
        )
        return reg
    }

    private data class TestFixture(
        val items: ItemRegistry,
        val players: PlayerRegistry,
        val mobs: MobRegistry,
        val outbound: LocalOutboundBus,
        val clock: MutableClock,
        val combat: CombatSystem,
        val abilities: AbilitySystem,
    )

    private fun fixture(
        clockStart: Long = 0L,
        rngSeed: Long = 42L,
    ): TestFixture {
        val items = ItemRegistry()
        val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val clock = MutableClock(clockStart)
        val combat =
            CombatSystem(
                players,
                mobs,
                items,
                outbound,
                clock = clock,
                rng = Random(1),
                tickMillis = 1_000L,
            )
        val registry = buildRegistry()
        val abilities =
            AbilitySystem(
                registry = registry,
                players = players,
                combat = combat,
                outbound = outbound,
                clock = clock,
                rng = Random(rngSeed),
            )
        return TestFixture(items, players, mobs, outbound, clock, combat, abilities)
    }

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

    private fun textMessages(
        events: List<OutboundEvent>,
        sid: SessionId,
    ): List<String> =
        events
            .filterIsInstance<OutboundEvent.SendText>()
            .filter { it.sessionId == sid }
            .map { it.text }

    @Test
    fun `damage spell hits mob`() =
        runTest {
            val f = fixture()
            val sid = SessionId(1L)
            login(f.players, sid, "Caster1")
            f.abilities.syncAbilities(sid, 1)

            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            f.mobs.upsert(mob)
            drainOutbound(f.outbound) // clear login messages

            val err = f.abilities.cast(sid, "magic_missile", "rat")
            assertNull(err, "Expected cast to succeed")

            val updatedMob = f.mobs.get(mob.id)
            assertNotNull(updatedMob)
            assertTrue(updatedMob!!.hp < 20, "Expected mob to take damage, hp=${updatedMob.hp}")

            val player = f.players.get(sid)!!
            assertTrue(player.mana < player.maxMana, "Expected mana to be deducted")

            val messages = textMessages(drainOutbound(f.outbound), sid)
            assertTrue(messages.any { it.contains("Magic Missile") && it.contains("damage") })
        }

    @Test
    fun `heal restores HP`() =
        runTest {
            val f = fixture()
            val sid = SessionId(2L)
            login(f.players, sid, "Healer2")
            f.abilities.syncAbilities(sid, 1)

            val player = f.players.get(sid)!!
            player.hp = 3 // wound the player
            drainOutbound(f.outbound)

            val err = f.abilities.cast(sid, "heal", null)
            assertNull(err)

            assertTrue(player.hp > 3, "Expected HP to increase after heal, hp=${player.hp}")
            assertTrue(player.mana < player.maxMana, "Expected mana deducted")
        }

    @Test
    fun `insufficient mana returns error`() =
        runTest {
            val f = fixture()
            val sid = SessionId(3L)
            login(f.players, sid, "NoMana3")
            f.abilities.syncAbilities(sid, 1)
            f.players.get(sid)!!.mana = 0

            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            f.mobs.upsert(mob)

            val err = f.abilities.cast(sid, "magic_missile", "rat")
            assertNotNull(err)
            assertTrue(err!!.contains("mana"), "Expected mana error, got: $err")
        }

    @Test
    fun `cooldown enforcement`() =
        runTest {
            val f = fixture()
            val sid = SessionId(4L)
            login(f.players, sid, "Caster4")
            f.abilities.syncAbilities(sid, 1)
            drainOutbound(f.outbound)

            // First heal succeeds
            f.players.get(sid)!!.hp = 1
            val err1 = f.abilities.cast(sid, "heal", null)
            assertNull(err1)

            // Immediate second heal should be on cooldown
            f.players.get(sid)!!.hp = 1
            val err2 = f.abilities.cast(sid, "heal", null)
            assertNotNull(err2)
            assertTrue(err2!!.contains("cooldown"), "Expected cooldown error, got: $err2")

            // Advance past cooldown
            f.clock.advance(4000L)
            f.players.get(sid)!!.hp = 1
            val err3 = f.abilities.cast(sid, "heal", null)
            assertNull(err3, "Expected heal to succeed after cooldown")
        }

    @Test
    fun `spell kill grants XP and drops`() =
        runTest {
            val f = fixture()
            val sid = SessionId(5L)
            login(f.players, sid, "Killer5")
            f.abilities.syncAbilities(sid, 1)

            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 1, maxHp = 10, xpReward = 50L)
            f.mobs.upsert(mob)
            drainOutbound(f.outbound)

            val err = f.abilities.cast(sid, "magic_missile", "rat")
            assertNull(err)

            // Mob should be dead and removed
            assertNull(f.mobs.get(mob.id), "Expected mob to be removed after kill")

            val messages = textMessages(drainOutbound(f.outbound), sid)
            assertTrue(messages.any { it.contains("XP") }, "Expected XP message, got: $messages")
        }

    @Test
    fun `unknown spell returns error`() =
        runTest {
            val f = fixture()
            val sid = SessionId(6L)
            login(f.players, sid, "Caster6")
            f.abilities.syncAbilities(sid, 1)

            val err = f.abilities.cast(sid, "nonexistent", null)
            assertNotNull(err)
            assertTrue(err!!.contains("don't know"), "Expected unknown spell error, got: $err")
        }

    @Test
    fun `unlearned spell returns error`() =
        runTest {
            val f = fixture()
            val sid = SessionId(7L)
            login(f.players, sid, "Caster7")
            f.abilities.syncAbilities(sid, 1) // Level 1, doesn't know Fireball (requires 5)

            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            f.mobs.upsert(mob)

            val err = f.abilities.cast(sid, "fireball", "rat")
            assertNotNull(err)
            assertTrue(err!!.contains("haven't learned"), "Expected unlearned error, got: $err")
        }

    @Test
    fun `spell bypasses mob armor`() =
        runTest {
            val f = fixture(rngSeed = 100L) // fixed seed for deterministic damage
            val sid = SessionId(8L)
            login(f.players, sid, "Caster8")
            f.abilities.syncAbilities(sid, 1)

            // Mob with high armor
            val mob = MobState(MobId("demo:tank"), "a tank", roomId, hp = 50, maxHp = 50, armor = 100)
            f.mobs.upsert(mob)
            drainOutbound(f.outbound)

            val err = f.abilities.cast(sid, "magic_missile", "tank")
            assertNull(err)

            val updatedMob = f.mobs.get(mob.id)
            assertNotNull(updatedMob)
            // Spell damage bypasses armor, so at least minDamage=3 should apply
            assertTrue(updatedMob!!.hp <= 47, "Expected spell to bypass armor, hp=${updatedMob.hp}")
        }

    @Test
    fun `auto-target combat mob when no target specified`() =
        runTest {
            val f = fixture()
            val sid = SessionId(9L)
            login(f.players, sid, "Fighter9")
            f.abilities.syncAbilities(sid, 1)

            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 20, maxHp = 20)
            f.mobs.upsert(mob)
            f.combat.startCombat(sid, "rat")
            drainOutbound(f.outbound)

            // Cast without specifying target, should auto-target combat mob
            val err = f.abilities.cast(sid, "magic_missile", null)
            assertNull(err, "Expected auto-target to work")

            val updatedMob = f.mobs.get(mob.id)
            assertNotNull(updatedMob)
            assertTrue(updatedMob!!.hp < 20, "Expected mob to take damage via auto-target")
        }

    @Test
    fun `disconnect cleans up learned abilities and cooldowns`() =
        runTest {
            val f = fixture()
            val sid = SessionId(10L)
            login(f.players, sid, "Caster10")
            f.abilities.syncAbilities(sid, 1)

            f.abilities.onPlayerDisconnected(sid)

            val known = f.abilities.knownAbilities(sid)
            assertTrue(known.isEmpty(), "Expected no known abilities after disconnect")
        }

    @Test
    fun `syncAbilitiesAndReturnNew returns newly learned abilities`() =
        runTest {
            val f = fixture()
            val sid = SessionId(11L)
            login(f.players, sid, "Caster11")
            f.abilities.syncAbilities(sid, 1) // Level 1: magic_missile, heal

            // Level up to 5: should gain fireball
            val newNames = f.abilities.syncAbilitiesAndReturnNew(sid, 5)
            assertEquals(listOf("Fireball"), newNames, "Expected Fireball to be newly learned")
        }

    @Test
    fun `cast on enemy with no target and not in combat returns error`() =
        runTest {
            val f = fixture()
            val sid = SessionId(12L)
            login(f.players, sid, "Caster12")
            f.abilities.syncAbilities(sid, 1)

            val err = f.abilities.cast(sid, "magic_missile", null)
            assertNotNull(err)
            assertTrue(err!!.contains("target") || err.contains("combat"), "Expected target error, got: $err")
        }
}
