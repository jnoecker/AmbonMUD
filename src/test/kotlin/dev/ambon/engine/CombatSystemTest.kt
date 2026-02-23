package dev.ambon.engine

import dev.ambon.bus.LocalOutboundBus
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
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.domain.world.MobDrop
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

            val outbound = LocalOutboundBus()
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

            val outbound = LocalOutboundBus()
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
            // Warrior class adds +2 base damage → 1+2(weapon)+2(class)=5 total → mob hp: 10-5=5
            assertEquals(5, updatedMob!!.hp)
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

            val outbound = LocalOutboundBus()
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

            val outbound = LocalOutboundBus()
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

            val outbound = LocalOutboundBus()
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
    fun `mob death rolls guaranteed loot table drop`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            items.loadSpawns(
                listOf(
                    ItemSpawn(
                        instance =
                            ItemInstance(
                                ItemId("demo:fang"),
                                Item(keyword = "fang", displayName = "a wolf fang"),
                            ),
                    ),
                ),
            )
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob =
                MobState(
                    MobId("demo:wolf"),
                    "a wolf",
                    roomId,
                    hp = 1,
                    maxHp = 1,
                    drops = listOf(MobDrop(ItemId("demo:fang"), 1.0)),
                )
            mobs.upsert(mob)

            val outbound = LocalOutboundBus()
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

            val sid = SessionId(7L)
            login(players, sid, "Player7")
            val err = combat.startCombat(sid, "wolf")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            assertTrue(items.itemsInRoom(roomId).any { it.id.value == "demo:fang" })
        }

    @Test
    fun `mob death skips loot table drop when chance is zero`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            items.loadSpawns(
                listOf(
                    ItemSpawn(
                        instance =
                            ItemInstance(
                                ItemId("demo:fang"),
                                Item(keyword = "fang", displayName = "a wolf fang"),
                            ),
                    ),
                ),
            )
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob =
                MobState(
                    MobId("demo:wolf"),
                    "a wolf",
                    roomId,
                    hp = 1,
                    maxHp = 1,
                    drops = listOf(MobDrop(ItemId("demo:fang"), 0.0)),
                )
            mobs.upsert(mob)

            val outbound = LocalOutboundBus()
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

            val sid = SessionId(8L)
            login(players, sid, "Player8")
            val err = combat.startCombat(sid, "wolf")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            assertTrue(items.itemsInRoom(roomId).none { it.id.value == "demo:fang" })
        }

    @Test
    fun `mob kill awards xp and level up`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 1, maxHp = 1, xpReward = 50L)
            mobs.upsert(mob)

            val outbound = LocalOutboundBus()
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
                        rewards =
                            LevelRewardsConfig(
                                hpPerLevel = 3,
                                fullHealOnLevelUp = true,
                                manaPerLevel = 5,
                                fullManaOnLevelUp = true,
                            ),
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
            // Warrior class: hpPerLevel=4, manaPerLevel=1
            assertEquals(14, player.maxHp)
            assertEquals(14, player.hp)

            val messages =
                drainOutbound(outbound)
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }
            assertTrue(messages.contains("You gain 50 XP."))
            assertTrue(messages.contains("You reached level 2! (+4 max HP, +1 max Mana)"))
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

    @Test
    fun `mob armor reduces player effective damage to minimum 1`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            // armor=100 absorbs all player damage; minimum 1 must apply
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10, armor = 100)
            mobs.upsert(mob)

            val outbound = LocalOutboundBus()
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

            val sid = SessionId(1L)
            login(players, sid, "Tester1")
            combat.startCombat(sid, "rat")
            clock.advance(1_000L)
            combat.tick()

            // mob should lose at least 1 hp (minimum effective damage)
            assertTrue(mob.hp <= 9, "Expected mob hp <= 9, got ${mob.hp}")
            assertEquals(9, mob.hp)

            val messages = drainOutbound(outbound).filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(messages.any { it.contains("for 1 damage") }, "Expected 'for 1 damage' in: $messages")
        }

    @Test
    fun `mob armor reduces player damage by flat amount`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            // armor=2, player rolls fixed 5 → effective = 5-2 = 3
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10, armor = 2)
            mobs.upsert(mob)

            val outbound = LocalOutboundBus()
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
                    minDamage = 5,
                    maxDamage = 5,
                )

            val sid = SessionId(2L)
            login(players, sid, "Tester2")
            combat.startCombat(sid, "rat")
            clock.advance(1_000L)
            combat.tick()

            // Warrior class adds +2 damage → total attack=5+2=7, minus 2 armor = 5
            assertEquals(5, mob.hp, "Expected mob hp=5 (10 - (5+2-2)=5)")
            val messages = drainOutbound(outbound).filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(messages.any { it.contains("for 5 damage") }, "Expected 'for 5 damage' in: $messages")
        }

    @Test
    fun `mob uses its own damage range not global config`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            // mob has minDamage=10, maxDamage=10; global config has 1/1
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    roomId,
                    hp = 10,
                    maxHp = 10,
                    minDamage = 10,
                    maxDamage = 10,
                )
            mobs.upsert(mob)

            val outbound = LocalOutboundBus()
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
            login(players, sid, "Tester3")
            combat.startCombat(sid, "rat")
            clock.advance(1_000L)
            combat.tick()

            val player = players.get(sid)
            assertNotNull(player)
            // mob should have hit player for 9 (its own damage 10 minus warrior armor bonus 1)
            val messages = drainOutbound(outbound).filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(messages.any { it.contains("hits you for 9 damage") }, "Expected mob hit for 9, messages: $messages")
        }

    @Test
    fun `detailed combat feedback includes compact roll and armor summaries for both sides`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    roomId,
                    hp = 20,
                    maxHp = 20,
                    minDamage = 7,
                    maxDamage = 7,
                    armor = 2,
                )
            mobs.upsert(mob)

            val outbound = LocalOutboundBus()
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
                    minDamage = 5,
                    maxDamage = 5,
                    detailedFeedbackEnabled = true,
                )

            val sid = SessionId(9L)
            login(players, sid, "Tester9")
            combat.startCombat(sid, "rat")
            clock.advance(1_000L)
            combat.tick()

            val messages =
                drainOutbound(outbound)
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }

            // Warrior class: +2 damage bonus → roll 5 + 2 attack = 7, minus 2 mob armor = 5
            assertTrue(
                messages.any { it.contains("You hit a rat for 5 damage (roll 5 +atk 2, armor absorbed 2).") },
                "Expected detailed player hit feedback, messages: $messages",
            )
            // Warrior class: +1 armor bonus → mob roll 7 - 1 class armor = 6
            assertTrue(
                messages.any { it.contains("a rat hits you for 6 damage (roll 7, armor absorbed 1).") },
                "Expected detailed mob hit feedback, messages: $messages",
            )
        }

    @Test
    fun `detailed combat feedback shows min clamp when armor fully absorbs roll`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10, armor = 100)
            mobs.upsert(mob)

            val outbound = LocalOutboundBus()
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
                    detailedFeedbackEnabled = true,
                )

            val sid = SessionId(10L)
            login(players, sid, "Tester10")
            combat.startCombat(sid, "rat")
            clock.advance(1_000L)
            combat.tick()

            val messages =
                drainOutbound(outbound)
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }

            // Warrior class: +2 damage → roll 1 + 2 = 3, minus 100 armor → clamped to 1
            assertTrue(
                messages.any { it.contains("You hit a rat for 1 damage (roll 1 +atk 2, armor absorbed 2, min 1 applied).") },
                "Expected min-clamp feedback in player hit message, messages: $messages",
            )
        }

    @Test
    fun `detailed combat feedback can broadcast to room observers when enabled`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    roomId,
                    hp = 20,
                    maxHp = 20,
                    minDamage = 7,
                    maxDamage = 7,
                    armor = 2,
                )
            mobs.upsert(mob)

            val outbound = LocalOutboundBus()
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
                    minDamage = 5,
                    maxDamage = 5,
                    detailedFeedbackEnabled = true,
                    detailedFeedbackRoomBroadcastEnabled = true,
                )

            val fighterSid = SessionId(11L)
            val observerSid = SessionId(12L)
            login(players, fighterSid, "Fighter")
            login(players, observerSid, "Observer")
            combat.startCombat(fighterSid, "rat")
            clock.advance(1_000L)
            combat.tick()

            val observerMessages =
                drainOutbound(outbound)
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == observerSid }
                    .map { it.text }

            // Warrior class: +2 damage → roll 5 + 2 attack = 7, minus 2 mob armor = 5
            assertTrue(
                observerMessages.any { it.contains("[Combat] Fighter hits a rat for 5 damage (roll 5 +atk 2, armor absorbed 2).") },
                "Expected room observer player-hit feedback, messages: $observerMessages",
            )
            // Warrior class: +1 armor → mob roll 7 - 1 = 6
            assertTrue(
                observerMessages.any { it.contains("[Combat] a rat hits Fighter for 6 damage (roll 7, armor absorbed 1).") },
                "Expected room observer mob-hit feedback, messages: $observerMessages",
            )
        }

    @Test
    fun `player slain by mob shows death summary and safe respawn message`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            // mob hits hard enough to one-shot the player
            val mob =
                MobState(
                    MobId("demo:ogre"),
                    "an ogre",
                    roomId,
                    hp = 100,
                    maxHp = 100,
                    minDamage = 50,
                    maxDamage = 50,
                )
            mobs.upsert(mob)

            val outbound = LocalOutboundBus()
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

            val sid = SessionId(20L)
            login(players, sid, "Victim")
            combat.startCombat(sid, "ogre")
            // drain the "You attack an ogre." message
            drainOutbound(outbound)

            clock.advance(1_000L)
            combat.tick()

            val messages =
                drainOutbound(outbound)
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }

            assertTrue(
                messages.any { it.contains("You have been slain by an ogre.") },
                "Expected death summary message, got: $messages",
            )
            assertTrue(
                messages.any { it.contains("You are safe now") },
                "Expected safe respawn message, got: $messages",
            )
        }

    @Test
    fun `player death broadcasts to room observers`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob =
                MobState(
                    MobId("demo:ogre"),
                    "an ogre",
                    roomId,
                    hp = 100,
                    maxHp = 100,
                    minDamage = 50,
                    maxDamage = 50,
                )
            mobs.upsert(mob)

            val outbound = LocalOutboundBus()
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

            val fighterSid = SessionId(21L)
            val observerSid = SessionId(22L)
            login(players, fighterSid, "Fighter")
            login(players, observerSid, "Observer")
            combat.startCombat(fighterSid, "ogre")
            drainOutbound(outbound)

            clock.advance(1_000L)
            combat.tick()

            val observerMessages =
                drainOutbound(outbound)
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == observerSid }
                    .map { it.text }

            assertTrue(
                observerMessages.any { it.contains("Fighter has been slain by an ogre.") },
                "Expected death broadcast to observer, got: $observerMessages",
            )
        }

    @Test
    fun `player at zero hp shows collapse message and safe respawn`() =
        runTest {
            val roomId = RoomId("zone:room")
            val items = ItemRegistry()
            val players = PlayerRegistry(roomId, InMemoryPlayerRepository(), items)
            val mobs = MobRegistry()
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 100, maxHp = 100)
            mobs.upsert(mob)

            val outbound = LocalOutboundBus()
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

            val sid = SessionId(23L)
            login(players, sid, "Wounded")
            // manually set HP to 0 before the combat tick
            players.get(sid)!!.hp = 0
            combat.startCombat(sid, "rat")
            drainOutbound(outbound)

            clock.advance(1_000L)
            combat.tick()

            val messages =
                drainOutbound(outbound)
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }

            assertTrue(
                messages.any { it.contains("You collapse, too wounded to keep fighting.") },
                "Expected collapse message, got: $messages",
            )
            assertTrue(
                messages.any { it.contains("You are safe now") },
                "Expected safe respawn message, got: $messages",
            )
        }

    private fun drainOutbound(outbound: LocalOutboundBus): List<OutboundEvent> {
        val events = mutableListOf<OutboundEvent>()
        while (true) {
            val event = outbound.tryReceive().getOrNull() ?: break
            events.add(event)
        }
        return events
    }
}
