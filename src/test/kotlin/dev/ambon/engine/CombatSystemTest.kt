package dev.ambon.engine

import dev.ambon.config.LevelRewardsConfig
import dev.ambon.config.ProgressionConfig
import dev.ambon.config.XpCurveConfig
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class CombatSystemTest {
    @Test
    fun `combat tick damages both sides`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10)
            mobs.upsert(mob)

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val clock = MutableClock(0L)
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

            val sid = SessionId(1L)
            login(players, sid, "Player1")

            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            val player = players.get(sid)
            assertNotNull(player)
            assertTrue(player!!.hp < player.maxHp, "Expected player to take damage")

            val updatedMob = mobs.get(mob.id)
            assertNotNull(updatedMob)
            assertTrue(updatedMob!!.hp < updatedMob.maxHp, "Expected mob to take damage")
        }

    @Test
    fun `attack bonus adds flat damage`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10)
            mobs.upsert(mob)

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val clock = MutableClock(0L)
            val combat =
                CombatSystem(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(1),
                    tickMillis = 1_000L,
                    minDamage = 1,
                    maxDamage = 1,
                )

            val sid = SessionId(3L)
            login(players, sid, "Player3")

            equipItem(
                items,
                sid,
                roomId,
                ItemInstance(
                    ItemId("demo:dagger"),
                    Item(keyword = "dagger", displayName = "a dagger", slot = ItemSlot.HAND, damage = 2),
                ),
            )

            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            val updatedMob = mobs.get(mob.id)
            assertNotNull(updatedMob)
            assertEquals(7, updatedMob!!.hp)
        }

    @Test
    fun `defense bonus increases max hp pool`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10)
            mobs.upsert(mob)

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val clock = MutableClock(0L)
            val combat =
                CombatSystem(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(1),
                    tickMillis = 1_000L,
                    minDamage = 1,
                    maxDamage = 1,
                )

            val sid = SessionId(4L)
            login(players, sid, "Player4")

            equipItem(
                items,
                sid,
                roomId,
                ItemInstance(
                    ItemId("demo:cap"),
                    Item(keyword = "cap", displayName = "a cap", slot = ItemSlot.HEAD, armor = 2),
                ),
            )

            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            val player = players.get(sid)
            assertNotNull(player)
            assertEquals(12, player!!.maxHp)
            assertEquals(11, player.hp)
        }

    @Test
    fun `unequipping armor clamps hp to new max without reducing current hp`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val clock = MutableClock(0L)
            val combat =
                CombatSystem(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(1),
                    tickMillis = 1_000L,
                    minDamage = 1,
                    maxDamage = 1,
                )

            val sid = SessionId(6L)
            login(players, sid, "Player6")

            equipItem(
                items,
                sid,
                roomId,
                ItemInstance(
                    ItemId("demo:helm"),
                    Item(keyword = "helm", displayName = "a helm", slot = ItemSlot.HEAD, armor = 2),
                ),
            )
            combat.syncPlayerDefense(sid)

            val player = players.get(sid)!!
            player.hp = 8

            items.unequip(sid, ItemSlot.HEAD)
            combat.syncPlayerDefense(sid)

            assertEquals(10, player.maxHp)
            assertEquals(8, player.hp)
        }

    @Test
    fun `mob death drops items and removes mob`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:owl"), "an owl", roomId, hp = 1, maxHp = 1)
            mobs.upsert(mob)
            items.addMobItem(
                mob.id,
                ItemInstance(ItemId("demo:feather"), Item(keyword = "feather", displayName = "a black feather")),
            )

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val clock = MutableClock(0L)
            val combat =
                CombatSystem(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(2),
                    tickMillis = 1_000L,
                )

            val sid = SessionId(2L)
            login(players, sid, "Player2")

            val err = combat.startCombat(sid, "owl")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            assertNull(mobs.get(mob.id), "Expected mob to be removed after death")
            assertTrue(items.itemsInMob(mob.id).isEmpty(), "Expected mob inventory to be cleared")
            assertEquals(1, items.itemsInRoom(roomId).size, "Expected dropped item in room")
        }

    @Test
    fun `mob kill awards xp and level up`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 1, maxHp = 1)
            mobs.upsert(mob)

            val outbound = Channel<OutboundEvent>(Channel.UNLIMITED)
            val clock = MutableClock(0L)
            val progression =
                PlayerProgression(
                    ProgressionConfig(
                        maxLevel = 20,
                        xp =
                            XpCurveConfig(
                                baseXp = 50L,
                                exponent = 2.0,
                                linearXp = 0L,
                                multiplier = 1.0,
                                defaultKillXp = 50L,
                            ),
                        rewards = LevelRewardsConfig(hpPerLevel = 3, fullHealOnLevelUp = true),
                    ),
                )
            val combat =
                CombatSystem(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(5),
                    tickMillis = 1_000L,
                    minDamage = 1,
                    maxDamage = 1,
                    progression = progression,
                )

            val sid = SessionId(5L)
            login(players, sid, "Player5")

            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            val player = players.get(sid)
            assertNotNull(player)
            assertEquals(2, player!!.level)
            assertEquals(50L, player.xpTotal)
            assertEquals(13, player.maxHp)
            assertEquals(13, player.hp)

            val messages =
                drainOutbound(outbound)
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }
            assertTrue(messages.contains("You gain 50 XP."))
            assertTrue(messages.contains("You reached level 2! (+3 max HP)"))
        }

    private fun equipItem(
        items: ItemRegistry,
        sessionId: SessionId,
        roomId: RoomId,
        instance: ItemInstance,
    ) {
        items.addRoomItem(roomId, instance)
        val moved = items.takeFromRoom(sessionId, roomId, instance.item.keyword)
        requireNotNull(moved) { "Expected to move item '${instance.item.keyword}' into inventory" }
        val result = items.equipFromInventory(sessionId, instance.item.keyword)
        require(result is ItemRegistry.EquipResult.Equipped) { "Expected to equip '${instance.item.keyword}', got $result" }
    }

    private suspend fun login(
        players: PlayerRegistry,
        sessionId: SessionId,
        name: String,
    ) {
        val res = players.login(sessionId, name, "password")
        require(res == LoginResult.Ok) { "Login failed: $res" }
    }

    private fun drainOutbound(outbound: Channel<OutboundEvent>): List<OutboundEvent> {
        val events = mutableListOf<OutboundEvent>()
        while (true) {
            val event = outbound.tryReceive().getOrNull() ?: break
            events.add(event)
        }
        return events
    }
}
