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
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.domain.world.MobDrop
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.EffectType
import dev.ambon.engine.status.StackBehavior
import dev.ambon.engine.status.StatModifiers
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
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
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10)
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(1),
                    tickMillis = 1_000L,
                )

            val sid = SessionId(1L)
            players.loginOrFail(sid, "Player1")

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
    fun `combat does not resolve before combat tick interval`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10)
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(1),
                    tickMillis = 1_000L,
                )

            val sid = SessionId(99L)
            players.loginOrFail(sid, "Player99")

            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            combat.tick()

            val player = players.get(sid)
            assertNotNull(player)
            assertEquals(player!!.maxHp, player.hp, "Expected no player damage before combat tick interval")

            val updatedMob = mobs.get(mob.id)
            assertNotNull(updatedMob)
            assertEquals(updatedMob!!.maxHp, updatedMob.hp, "Expected no mob damage before combat tick interval")
        }

    @Test
    fun `attack bonus adds flat damage`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10)
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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
            players.loginOrFail(sid, "Player3")

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
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10)
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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
            players.loginOrFail(sid, "Player4")

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
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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
            players.loginOrFail(sid, "Player6")

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
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            val mob = MobState(MobId("demo:owl"), "an owl", roomId, hp = 1, maxHp = 1)
            mobs.upsert(mob)
            items.addMobItem(
                mob.id,
                ItemInstance(ItemId("demo:feather"), Item(keyword = "feather", displayName = "a black feather")),
            )

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(2),
                    tickMillis = 1_000L,
                )

            val sid = SessionId(2L)
            players.loginOrFail(sid, "Player2")

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
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
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
            val players = fixture.players
            val mobs = fixture.mobs
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

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(2),
                    tickMillis = 1_000L,
                )

            val sid = SessionId(7L)
            players.loginOrFail(sid, "Player7")
            val err = combat.startCombat(sid, "wolf")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            assertTrue(items.itemsInRoom(roomId).any { it.id.value == "demo:fang" })
        }

    @Test
    fun `mob death skips loot table drop when chance is zero`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
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
            val players = fixture.players
            val mobs = fixture.mobs
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

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(2),
                    tickMillis = 1_000L,
                )

            val sid = SessionId(8L)
            players.loginOrFail(sid, "Player8")
            val err = combat.startCombat(sid, "wolf")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            assertTrue(items.itemsInRoom(roomId).none { it.id.value == "demo:fang" })
        }

    @Test
    fun `mob kill awards xp and level up`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 1, maxHp = 1, xpReward = 50L)
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock
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
                fixture.buildCombat(
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
            players.loginOrFail(sid, "Player5")

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
                outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }
            assertTrue(messages.contains("You gain 50 XP."))
            assertTrue(messages.contains("You reached level 2! (+3 max HP, +2 max Mana)"))
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

    @Test
    fun `mob armor reduces player effective damage to minimum 1`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            // armor=100 absorbs all player damage; minimum 1 must apply
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10, armor = 100)
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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
            players.loginOrFail(sid, "Tester1")
            combat.startCombat(sid, "rat")
            clock.advance(1_000L)
            combat.tick()

            // mob should lose at least 1 hp (minimum effective damage)
            assertTrue(mob.hp <= 9, "Expected mob hp <= 9, got ${mob.hp}")
            assertEquals(9, mob.hp)

            val messages = outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(messages.any { it.contains("for 1 damage") }, "Expected 'for 1 damage' in: $messages")
        }

    @Test
    fun `mob armor reduces player damage by flat amount`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            // armor=2, player rolls fixed 5 → effective = 5-2 = 3
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10, armor = 2)
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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
            players.loginOrFail(sid, "Tester2")
            combat.startCombat(sid, "rat")
            clock.advance(1_000L)
            combat.tick()

            assertEquals(7, mob.hp, "Expected mob hp=7 (10 - (5-2)=3)")
            val messages = outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(messages.any { it.contains("for 3 damage") }, "Expected 'for 3 damage' in: $messages")
        }

    @Test
    fun `mob uses its own damage range not global config`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
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

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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
            players.loginOrFail(sid, "Tester3")
            combat.startCombat(sid, "rat")
            clock.advance(1_000L)
            combat.tick()

            val player = players.get(sid)
            assertNotNull(player)
            // mob should have hit player for 10 (its own damage), not 1 (global config)
            val messages = outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(messages.any { it.contains("hits you for 10 damage") }, "Expected mob hit for 10, messages: $messages")
        }

    @Test
    fun `detailed combat feedback includes compact roll and armor summaries for both sides`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
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

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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
            players.loginOrFail(sid, "Tester9")
            combat.startCombat(sid, "rat")
            clock.advance(1_000L)
            combat.tick()

            val messages =
                outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }

            assertTrue(
                messages.any { it.contains("You hit a rat for 3 damage (roll 5, armor absorbed 2).") },
                "Expected detailed player hit feedback, messages: $messages",
            )
            assertTrue(
                messages.any { it.contains("a rat hits you for 7 damage (roll 7, armor absorbed 0).") },
                "Expected detailed mob hit feedback, messages: $messages",
            )
        }

    @Test
    fun `detailed combat feedback shows min clamp when armor fully absorbs roll`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10, armor = 100)
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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
            players.loginOrFail(sid, "Tester10")
            combat.startCombat(sid, "rat")
            clock.advance(1_000L)
            combat.tick()

            val messages =
                outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }

            assertTrue(
                messages.any { it.contains("You hit a rat for 1 damage (roll 1, armor absorbed 0, min 1 applied).") },
                "Expected min-clamp feedback in player hit message, messages: $messages",
            )
        }

    @Test
    fun `detailed combat feedback can broadcast to room observers when enabled`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
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

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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
            players.loginOrFail(fighterSid, "Fighter")
            players.loginOrFail(observerSid, "Observer")
            combat.startCombat(fighterSid, "rat")
            clock.advance(1_000L)
            combat.tick()

            val observerMessages =
                outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == observerSid }
                    .map { it.text }

            assertTrue(
                observerMessages.any { it.contains("[Combat] Fighter hits a rat for 3 damage (roll 5, armor absorbed 2).") },
                "Expected room observer player-hit feedback, messages: $observerMessages",
            )
            assertTrue(
                observerMessages.any { it.contains("[Combat] a rat hits Fighter for 7 damage (roll 7, armor absorbed 0).") },
                "Expected room observer mob-hit feedback, messages: $observerMessages",
            )
        }

    @Test
    fun `player slain by mob shows death summary and safe respawn message`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
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

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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
            players.loginOrFail(sid, "Victim")
            combat.startCombat(sid, "ogre")
            // drain the "You attack an ogre." message
            outbound.drainAll()

            clock.advance(1_000L)
            combat.tick()

            val messages =
                outbound
                    .drainAll()
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
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
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

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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
            players.loginOrFail(fighterSid, "Fighter")
            players.loginOrFail(observerSid, "Observer")
            combat.startCombat(fighterSid, "ogre")
            outbound.drainAll()

            clock.advance(1_000L)
            combat.tick()

            val observerMessages =
                outbound
                    .drainAll()
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
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 100, maxHp = 100)
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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
            players.loginOrFail(sid, "Wounded")
            // manually set HP to 0 before the combat tick
            players.get(sid)!!.hp = 0
            combat.startCombat(sid, "rat")
            outbound.drainAll()

            clock.advance(1_000L)
            combat.tick()

            val messages =
                outbound
                    .drainAll()
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

    @Test
    fun `stunned player skips attack but mob still attacks`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 10, maxHp = 10, minDamage = 1, maxDamage = 1)
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock

            val statusRegistry = StatusEffectRegistry()
            statusRegistry.register(
                StatusEffectDefinition(
                    id = StatusEffectId("stun"),
                    displayName = "Stun",
                    effectType = EffectType.STUN,
                    durationMs = 5000,
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
                    rng = Random(1),
                    markVitalsDirty = {},
                    markMobHpDirty = {},
                    markStatusDirty = {},
                )
            val combat =
                fixture.buildCombat(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(1),
                    tickMillis = 1_000L,
                    minDamage = 5,
                    maxDamage = 5,
                    statusEffects = statusEffects,
                )

            val sid = SessionId(30L)
            players.loginOrFail(sid, "StunTest")
            combat.startCombat(sid, "rat")
            outbound.drainAll()

            // Apply stun to the player
            statusEffects.applyToPlayer(sid, StatusEffectId("stun"))

            clock.advance(1_000L)
            combat.tick()

            // Mob should still be at full HP (stunned player can't attack)
            assertEquals(10, mob.hp, "Stunned player should not damage mob")
            // Player should take damage from mob
            val player = players.get(sid)!!
            assertTrue(player.hp < player.maxHp, "Mob should still damage stunned player")
        }

    @Test
    fun `shield absorbs mob damage in combat`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 100, maxHp = 100, minDamage = 5, maxDamage = 5)
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock

            val statusRegistry = StatusEffectRegistry()
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
                    rng = Random(1),
                    markVitalsDirty = {},
                    markMobHpDirty = {},
                    markStatusDirty = {},
                )
            val combat =
                fixture.buildCombat(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(1),
                    tickMillis = 1_000L,
                    minDamage = 1,
                    maxDamage = 1,
                    statusEffects = statusEffects,
                )

            val sid = SessionId(31L)
            players.loginOrFail(sid, "ShieldTest")
            combat.startCombat(sid, "rat")
            outbound.drainAll()

            // Apply shield to the player
            statusEffects.applyToPlayer(sid, StatusEffectId("shield"))

            clock.advance(1_000L)
            combat.tick()

            // Player should take no damage (shield absorbs the 5 damage)
            val player = players.get(sid)!!
            assertEquals(player.maxHp, player.hp, "Shield should absorb all mob damage")
        }

    @Test
    fun `stat buff adds to str damage bonus`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            // mob with 0 armor so we can see exact damage
            val mob = MobState(MobId("demo:rat"), "a rat", roomId, hp = 50, maxHp = 50, minDamage = 1, maxDamage = 1)
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock

            val statusRegistry = StatusEffectRegistry()
            statusRegistry.register(
                StatusEffectDefinition(
                    id = StatusEffectId("buff"),
                    displayName = "Buff",
                    effectType = EffectType.STAT_BUFF,
                    durationMs = 60000,
                    statMods = StatModifiers(str = 6),
                ),
            )
            val statusEffects =
                StatusEffectSystem(
                    registry = statusRegistry,
                    players = players,
                    mobs = mobs,
                    outbound = outbound,
                    clock = clock,
                    rng = Random(1),
                    markVitalsDirty = {},
                    markMobHpDirty = {},
                    markStatusDirty = {},
                )
            val combat =
                fixture.buildCombat(
                    players,
                    mobs,
                    items,
                    outbound,
                    clock = clock,
                    rng = Random(1),
                    tickMillis = 1_000L,
                    minDamage = 3,
                    maxDamage = 3,
                    statusEffects = statusEffects,
                    strDivisor = 3,
                )

            val sid = SessionId(32L)
            players.loginOrFail(sid, "BuffTest")

            // Apply +6 STR buff → +2 bonus damage (6/3)
            statusEffects.applyToPlayer(sid, StatusEffectId("buff"))

            combat.startCombat(sid, "rat")
            outbound.drainAll()

            clock.advance(1_000L)
            combat.tick()

            // Damage should be 3 (base) + 2 (str bonus) = 5
            assertEquals(45, mob.hp, "STR buff should add bonus damage")
        }

    @Test
    fun `mob kill awards gold from gold range`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    roomId,
                    hp = 1,
                    maxHp = 1,
                    goldMin = 5L,
                    goldMax = 5L,
                )
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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

            val sid = SessionId(30L)
            players.loginOrFail(sid, "GoldHunter")
            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            val player = players.get(sid)
            assertNotNull(player)
            assertEquals(5L, player!!.gold)

            val messages =
                outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }
            assertTrue(messages.any { it.contains("You find 5 gold") }, "Expected gold drop message, got: $messages")
        }

    @Test
    fun `mob with zero gold range awards no gold`() =
        runTest {
            val fixture = CombatTestFixture()
            val roomId = fixture.roomId
            val items = fixture.items
            val players = fixture.players
            val mobs = fixture.mobs
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    roomId,
                    hp = 1,
                    maxHp = 1,
                    goldMin = 0L,
                    goldMax = 0L,
                )
            mobs.upsert(mob)

            val outbound = fixture.outbound
            val clock = fixture.clock
            val combat =
                fixture.buildCombat(
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

            val sid = SessionId(31L)
            players.loginOrFail(sid, "NoGold")
            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            clock.advance(1_000L)
            combat.tick()

            val player = players.get(sid)
            assertNotNull(player)
            assertEquals(0L, player!!.gold)

            val messages =
                outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }
            assertTrue(messages.none { it.contains("gold") && it.contains("find") }, "Expected no gold message, got: $messages")
        }
}
