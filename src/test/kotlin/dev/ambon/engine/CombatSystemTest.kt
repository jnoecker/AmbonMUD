package dev.ambon.engine

import dev.ambon.config.DeathConfig
import dev.ambon.config.LevelRewardsConfig
import dev.ambon.config.ProgressionConfig
import dev.ambon.config.UnderLevelXpBonusConfig
import dev.ambon.config.XpCurveConfig
import dev.ambon.domain.DamageRange
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.domain.world.MobDrop
import dev.ambon.engine.events.CombatEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectDefinition
import dev.ambon.engine.status.StatusEffectId
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.deterministicMeleeBindings
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
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 10, maxHp = 10)
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1))

            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Player1")

            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            fixture.tickCombat(combat)

            val player = fixture.players.get(sid)
            assertNotNull(player)
            assertTrue(player!!.hp < player.maxHp, "Expected player to take damage")

            val updatedMob = fixture.mobs.get(mob.id)
            assertNotNull(updatedMob)
            assertTrue(updatedMob!!.hp < updatedMob.maxHp, "Expected mob to take damage")
        }

    @Test
    fun `combat does not resolve before combat tick interval`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 10, maxHp = 10)
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1))

            val sid = SessionId(99L)
            fixture.players.loginOrFail(sid, "Player99")

            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            // tick without advancing clock — should not resolve
            combat.tick()

            val player = fixture.players.get(sid)
            assertNotNull(player)
            assertEquals(player!!.maxHp, player.hp, "Expected no player damage before combat tick interval")

            val updatedMob = fixture.mobs.get(mob.id)
            assertNotNull(updatedMob)
            assertEquals(updatedMob!!.maxHp, updatedMob.hp, "Expected no mob damage before combat tick interval")
        }

    @Test
    fun `attack bonus adds flat damage`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 10, maxHp = 10)
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)

            val sid = SessionId(3L)
            fixture.players.loginOrFail(sid, "Player3")

            fixture.equipItem(
                sid,
                ItemInstance(
                    ItemId("demo:dagger"),
                    Item(keyword = "dagger", displayName = "a dagger", slot = ItemSlot.WEAPON, damage = 2),
                ),
            )

            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            fixture.tickCombat(combat)

            val updatedMob = fixture.mobs.get(mob.id)
            assertNotNull(updatedMob)
            assertEquals(7, updatedMob!!.hp)
        }

    @Test
    fun `equipment armor mitigates incoming mob damage`() =
        runTest {
            val fixture = CombatTestFixture()
            // Mob hits for a flat 20 damage so the mitigation math is easy to read.
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    fixture.roomId,
                    hp = 100,
                    maxHp = 100,
                    damage = DamageRange(20, 20),
                )
            fixture.mobs.upsert(mob)

            // Default meleeArmorMitigationK = 20 → armor 20 = 50% reduction
            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)

            val sid = SessionId(4L)
            fixture.players.loginOrFail(sid, "Player4")
            val player = fixture.players.get(sid)!!
            player.maxHp = 100
            player.hp = 100

            fixture.equipItem(
                sid,
                ItemInstance(
                    ItemId("demo:plate"),
                    Item(keyword = "plate", displayName = "plate mail", slot = ItemSlot.BODY, armor = 20),
                ),
            )

            assertNull(combat.startCombat(sid, "rat"))
            fixture.tickCombat(combat)

            // Mob rolled 20, armor 20 with K=20 → mitigation 20/(20+20) = 50%, post-armor 10.
            assertEquals(90, player.hp, "Expected 50% armor mitigation: 100 - (20 * 0.5) = 90")
        }

    @Test
    fun `armor mitigation is multiplicative so high armor stays meaningful versus big hits`() =
        runTest {
            val fixture = CombatTestFixture()
            // Massive mob damage simulates an end-game over-level encounter.
            val mob =
                MobState(
                    MobId("demo:dragon"),
                    "a dragon",
                    fixture.roomId,
                    hp = 1000,
                    maxHp = 1000,
                    damage = DamageRange(1000, 1000),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)
            val sid = SessionId(5L)
            fixture.players.loginOrFail(sid, "Tank")
            val player = fixture.players.get(sid)!!
            player.maxHp = 10_000
            player.hp = 10_000

            fixture.equipItem(
                sid,
                ItemInstance(
                    ItemId("demo:fortress"),
                    Item(keyword = "fortress", displayName = "fortress armor", slot = ItemSlot.BODY, armor = 60),
                ),
            )

            assertNull(combat.startCombat(sid, "dragon"))
            fixture.tickCombat(combat)

            // armor 60 with K=20 → mitigation 60/80 = 75%, post-armor 250 from a 1000 hit.
            // Critically, the percentage doesn't shrink as raw damage grows — that's the
            // whole point vs the old `raw - armor` shape, which would have been 1000 - 60 = 940.
            assertEquals(9750, player.hp, "Expected 75% mitigation of a 1000 hit: 10000 - 250")
        }

    @Test
    fun `mob death drops items and removes mob`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:owl"), "an owl", fixture.roomId, hp = 1, maxHp = 1)
            fixture.mobs.upsert(mob)
            fixture.items.addMobItem(
                mob.id,
                ItemInstance(ItemId("demo:feather"), Item(keyword = "feather", displayName = "a black feather")),
            )

            val combat = fixture.buildCombat(rng = Random(2))

            val sid = SessionId(2L)
            fixture.players.loginOrFail(sid, "Player2")
            fixture.players.setAutolootEnabled(sid, false)

            val err = combat.startCombat(sid, "owl")
            assertNull(err)

            fixture.tickCombat(combat)

            assertNull(fixture.mobs.get(mob.id), "Expected mob to be removed after death")
            assertTrue(fixture.items.itemsInMob(mob.id).isEmpty(), "Expected mob inventory to be cleared")
            assertEquals(1, fixture.items.itemsInRoom(fixture.roomId).size, "Expected dropped item in room")
        }

    /**
     * Regression test for GH #1034: one-shotting a very weak enemy (e.g. a
     * 1-HP crane) must still emit a MeleeHit CombatEvent *before* the Kill
     * CombatEvent.  The web client relies on seeing the hit first so it can
     * animate the strike landing before the death animation takes over —
     * without this ordering the fight visually "freezes" and ends in one
     * silent frame.  The hit text must also precede the death text.
     */
    @Test
    fun `one-shot kill emits melee hit combat event before kill event`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:crane"),
                    "a crane",
                    fixture.roomId,
                    hp = 1,
                    maxHp = 1,
                    xpReward = 1L,
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)

            val sid = SessionId(42L)
            fixture.players.loginOrFail(sid, "Slayer")

            val combatEvents = mutableListOf<CombatEvent>()
            combat.onCombatEvent = { _, event -> combatEvents += event }

            assertNull(combat.startCombat(sid, "crane"))
            fixture.tickCombat(combat)

            // Server must emit at least a MeleeHit before the Kill so the
            // client can animate an attack round.
            val hitIdx = combatEvents.indexOfFirst { it is CombatEvent.MeleeHit }
            val killIdx = combatEvents.indexOfFirst { it is CombatEvent.Kill }
            assertTrue(hitIdx >= 0, "Expected a MeleeHit combat event, got: $combatEvents")
            assertTrue(killIdx >= 0, "Expected a Kill combat event, got: $combatEvents")
            assertTrue(
                hitIdx < killIdx,
                "Expected MeleeHit (idx $hitIdx) to precede Kill (idx $killIdx) in $combatEvents",
            )

            // The mob should be dead.
            assertNull(fixture.mobs.get(mob.id))

            // The "You hit ..." text must also precede the "... dies." text in
            // the outbound stream so telnet clients see the swing land.
            val texts =
                fixture.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .map { it.text }
            val hitTextIdx = texts.indexOfFirst { it.startsWith("You hit ") && it.contains("crane") }
            val deathTextIdx = texts.indexOfFirst { it == "a crane dies." }
            assertTrue(hitTextIdx >= 0, "Expected a 'You hit a crane' message in: $texts")
            assertTrue(deathTextIdx >= 0, "Expected 'a crane dies.' message in: $texts")
            assertTrue(
                hitTextIdx < deathTextIdx,
                "Expected hit text (idx $hitTextIdx) before death text (idx $deathTextIdx) in $texts",
            )
        }

    @Test
    fun `mob death rolls guaranteed loot table drop`() =
        runTest {
            val fixture = CombatTestFixture()
            fixture.items.loadSpawns(
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
            val mob =
                MobState(
                    MobId("demo:wolf"),
                    "a wolf",
                    fixture.roomId,
                    hp = 1,
                    maxHp = 1,
                    drops = listOf(MobDrop(ItemId("demo:fang"), 1.0)),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(2))

            val sid = SessionId(7L)
            fixture.players.loginOrFail(sid, "Player7")
            fixture.players.setAutolootEnabled(sid, false)
            val err = combat.startCombat(sid, "wolf")
            assertNull(err)

            fixture.tickCombat(combat)

            assertTrue(fixture.items.itemsInRoom(fixture.roomId).any { it.id.value == "demo:fang" })
        }

    @Test
    fun `mob death skips loot table drop when chance is zero`() =
        runTest {
            val fixture = CombatTestFixture()
            fixture.items.loadSpawns(
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
            val mob =
                MobState(
                    MobId("demo:wolf"),
                    "a wolf",
                    fixture.roomId,
                    hp = 1,
                    maxHp = 1,
                    drops = listOf(MobDrop(ItemId("demo:fang"), 0.0)),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(2))

            val sid = SessionId(8L)
            fixture.players.loginOrFail(sid, "Player8")
            val err = combat.startCombat(sid, "wolf")
            assertNull(err)

            fixture.tickCombat(combat)

            assertTrue(fixture.items.itemsInRoom(fixture.roomId).none { it.id.value == "demo:fang" })
        }

    @Test
    fun `mob kill awards xp and level up`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 1, maxHp = 1, xpReward = 50L)
            fixture.mobs.upsert(mob)

            val classRegistry =
                PlayerClassRegistry().also { reg ->
                    PlayerClassRegistryLoader.load(dev.ambon.test.testClassEngineConfig(), reg)
                }
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
                                hpScalingRate = 1.30,
                                fullHealOnLevelUp = true,
                                manaScalingRate = 1.25,
                                fullManaOnLevelUp = true,
                            ),
                    ),
                    classRegistry = classRegistry,
                )
            val combat =
                fixture.buildCombat(
                    rng = Random(5),
                    minDamage = 1,
                    maxDamage = 1,
                    progression = progression,
                )

            val sid = SessionId(5L)
            fixture.players.loginOrFail(sid, "Player5")

            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            fixture.tickCombat(combat)

            val player = fixture.players.get(sid)
            assertNotNull(player)
            assertEquals(2, player!!.level)
            assertEquals(50L, player.xpTotal)
            assertEquals(18, player.maxHp)
            assertEquals(18, player.hp)

            val messages =
                fixture.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }
            assertTrue(messages.contains("You gain 50 XP."))
            assertTrue(messages.contains("You reached level 2! (+8 max HP, +4 max Mana)"))
        }

    @Test
    fun `Kill combat event xpGained reflects the actual award including under-level bonus`() =
        runTest {
            val fixture = CombatTestFixture()
            // Mob out-levels the player by 2 (level 3 vs level 1), so the
            // under-level bonus (+0.15/level, capped) multiplies the reward.
            val mob =
                MobState(
                    MobId("demo:ogre"),
                    "an ogre",
                    fixture.roomId,
                    hp = 1,
                    maxHp = 1,
                    level = 3,
                    xpReward = 100L,
                )
            fixture.mobs.upsert(mob)

            val classRegistry =
                PlayerClassRegistry().also { reg ->
                    PlayerClassRegistryLoader.load(dev.ambon.test.testClassEngineConfig(), reg)
                }
            val progression =
                PlayerProgression(
                    ProgressionConfig(
                        maxLevel = 20,
                        xp =
                            XpCurveConfig(
                                // Large curve so the boosted reward never levels the player up,
                                // keeping the assertion focused on the toast value.
                                baseXp = 100_000L,
                                exponent = 2.0,
                                linearXp = 0L,
                                multiplier = 1.0,
                                defaultKillXp = 50L,
                                underLevelBonus = UnderLevelXpBonusConfig(enabled = true),
                            ),
                    ),
                    classRegistry = classRegistry,
                )
            val combat =
                fixture.buildCombat(
                    rng = Random(5),
                    minDamage = 1,
                    maxDamage = 1,
                    progression = progression,
                )

            val sid = SessionId(515L)
            fixture.players.loginOrFail(sid, "Underdog")

            val combatEvents = mutableListOf<CombatEvent>()
            combat.onCombatEvent = { _, event -> combatEvents += event }

            assertNull(combat.startCombat(sid, "ogre"))
            fixture.tickCombat(combat)

            // gap = 3 - 1 = 2 → +0.30 → 100 * 1.30 = 130 XP actually awarded.
            val kill = combatEvents.filterIsInstance<CombatEvent.Kill>().firstOrNull()
            assertNotNull(kill, "Expected a Kill combat event, got: $combatEvents")
            assertEquals(
                130L,
                kill!!.xpGained,
                "Kill toast must report the boosted award, not the base ${mob.xpReward} XP",
            )

            // The toast value must match the "You gain N XP." line the player sees.
            val messages =
                fixture.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }
            assertTrue(
                messages.contains("You gain 130 XP."),
                "Expected 'You gain 130 XP.' in: $messages",
            )
        }

    @Test
    fun `mob armor reduces player effective damage to minimum 1`() =
        runTest {
            val fixture = CombatTestFixture()
            // armor=100 absorbs all player damage; minimum 1 must apply
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 10, maxHp = 10, armor = 100)
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)

            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Tester1")
            combat.startCombat(sid, "rat")
            fixture.tickCombat(combat)

            // mob should lose at least 1 hp (minimum effective damage)
            assertTrue(mob.hp <= 9, "Expected mob hp <= 9, got ${mob.hp}")
            assertEquals(9, mob.hp)

            val messages = fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(messages.any { it.contains("for 1 damage") }, "Expected 'for 1 damage' in: $messages")
        }

    @Test
    fun `mob armor reduces player damage multiplicatively`() =
        runTest {
            val fixture = CombatTestFixture()
            // Player swings for raw 20 against mob armor 20. With K=20, mitigation = 20/40 = 50%,
            // so the mob takes 10 damage instead of 20.
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 100, maxHp = 100, armor = 20)
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 20, maxDamage = 20)

            val sid = SessionId(2L)
            fixture.players.loginOrFail(sid, "Tester2")
            combat.startCombat(sid, "rat")
            fixture.tickCombat(combat)

            assertEquals(90, mob.hp, "Expected 50% armor mitigation: 100 - 10 = 90")
            val messages = fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(messages.any { it.contains("for 10 damage") }, "Expected 'for 10 damage' in: $messages")
        }

    @Test
    fun `mob uses its own damage range not global config`() =
        runTest {
            val fixture = CombatTestFixture()
            // mob has minDamage=10, maxDamage=10; global config has 1/1
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    fixture.roomId,
                    hp = 10,
                    maxHp = 10,
                    damage = DamageRange(10, 10),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)

            val sid = SessionId(3L)
            fixture.players.loginOrFail(sid, "Tester3")
            combat.startCombat(sid, "rat")
            fixture.tickCombat(combat)

            val player = fixture.players.get(sid)
            assertNotNull(player)
            // mob should have hit player for 10 (its own damage), not 1 (global config)
            val messages = fixture.outbound.drainAll().filterIsInstance<OutboundEvent.SendText>().map { it.text }
            assertTrue(messages.any { it.contains("hits you for 10 damage") }, "Expected mob hit for 10, messages: $messages")
        }

    @Test
    fun `detailed combat feedback includes compact roll and armor summaries for both sides`() =
        runTest {
            val fixture = CombatTestFixture()
            // mob armor 20 with K=20 → 50% mitigation. Player rolls 10 → final 5.
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    fixture.roomId,
                    hp = 100,
                    maxHp = 100,
                    damage = DamageRange(7, 7),
                    armor = 20,
                )
            fixture.mobs.upsert(mob)

            val combat =
                fixture.buildCombat(
                    rng = Random(1),
                    minDamage = 10,
                    maxDamage = 10,
                    detailedFeedbackEnabled = true,
                )

            val sid = SessionId(9L)
            fixture.players.loginOrFail(sid, "Tester9")
            combat.startCombat(sid, "rat")
            fixture.tickCombat(combat)

            val messages =
                fixture.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }

            assertTrue(
                messages.any { it.contains("You hit a rat for 5 damage (roll 10, armor absorbed 5).") },
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
            // Massive armor + tiny roll → rounded-mitigated value is 0, clamp applies.
            // raw=1, armor=10000, K=20 → mitigation 10000/10020 ≈ 99.8% → 0.002 → clamps to 1.
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 10, maxHp = 10, armor = 10000)
            fixture.mobs.upsert(mob)

            val combat =
                fixture.buildCombat(
                    rng = Random(1),
                    minDamage = 1,
                    maxDamage = 1,
                    detailedFeedbackEnabled = true,
                )

            val sid = SessionId(10L)
            fixture.players.loginOrFail(sid, "Tester10")
            combat.startCombat(sid, "rat")
            fixture.tickCombat(combat)

            val messages =
                fixture.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }

            assertTrue(
                messages.any { it.contains("min 1 applied") && it.contains("You hit a rat for 1 damage") },
                "Expected min-clamp feedback in player hit message, messages: $messages",
            )
        }

    @Test
    fun `detailed combat feedback can broadcast to room observers when enabled`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    fixture.roomId,
                    hp = 100,
                    maxHp = 100,
                    damage = DamageRange(7, 7),
                    armor = 20,
                )
            fixture.mobs.upsert(mob)

            val combat =
                fixture.buildCombat(
                    rng = Random(1),
                    minDamage = 10,
                    maxDamage = 10,
                    detailedFeedbackEnabled = true,
                    detailedFeedbackRoomBroadcastEnabled = true,
                )

            val fighterSid = SessionId(11L)
            val observerSid = SessionId(12L)
            fixture.players.loginOrFail(fighterSid, "Fighter")
            fixture.players.loginOrFail(observerSid, "Observer")
            combat.startCombat(fighterSid, "rat")
            fixture.tickCombat(combat)

            val observerMessages =
                fixture.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == observerSid }
                    .map { it.text }

            assertTrue(
                observerMessages.any { it.contains("[Combat] Fighter hits a rat for 5 damage (roll 10, armor absorbed 5).") },
                "Expected room observer player-hit feedback, messages: $observerMessages",
            )
            assertTrue(
                observerMessages.any { it.contains("[Combat] a rat hits Fighter for 7 damage (roll 7, armor absorbed 0).") },
                "Expected room observer mob-hit feedback, messages: $observerMessages",
            )
        }

    @Test
    fun `player slain by mob shows death summary and sanctum arrival message`() =
        runTest {
            val fixture = CombatTestFixture()
            // mob hits hard enough to one-shot the player
            val mob =
                MobState(
                    MobId("demo:ogre"),
                    "an ogre",
                    fixture.roomId,
                    hp = 100,
                    maxHp = 100,
                    damage = DamageRange(50, 50),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)

            val sid = SessionId(20L)
            fixture.players.loginOrFail(sid, "Victim")
            combat.startCombat(sid, "ogre")
            // drain the "You attack an ogre." message
            fixture.outbound.drainAll()

            fixture.tickCombat(combat)

            val messages =
                fixture.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }

            assertTrue(
                messages.any { it.contains("You have been slain by an ogre.") },
                "Expected death summary message, got: $messages",
            )
            assertTrue(
                messages.any { it.contains("awaken in the sanctum") },
                "Expected sanctum arrival message, got: $messages",
            )
        }

    @Test
    fun `player death broadcasts to room observers`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:ogre"),
                    "an ogre",
                    fixture.roomId,
                    hp = 100,
                    maxHp = 100,
                    damage = DamageRange(50, 50),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)

            val fighterSid = SessionId(21L)
            val observerSid = SessionId(22L)
            fixture.players.loginOrFail(fighterSid, "Fighter")
            fixture.players.loginOrFail(observerSid, "Observer")
            combat.startCombat(fighterSid, "ogre")
            fixture.outbound.drainAll()

            fixture.tickCombat(combat)

            val observerMessages =
                fixture.outbound
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
    fun `player at zero hp shows collapse message and sanctum arrival`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 100, maxHp = 100)
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)

            val sid = SessionId(23L)
            fixture.players.loginOrFail(sid, "Wounded")
            // manually set HP to 0 before the combat tick
            fixture.players.get(sid)!!.hp = 0
            combat.startCombat(sid, "rat")
            fixture.outbound.drainAll()

            fixture.tickCombat(combat)

            val messages =
                fixture.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }

            assertTrue(
                messages.any { it.contains("You collapse, too wounded to keep fighting.") },
                "Expected collapse message, got: $messages",
            )
            assertTrue(
                messages.any { it.contains("awaken in the sanctum") },
                "Expected sanctum arrival message, got: $messages",
            )
        }

    @Test
    fun `stunned player skips attack but mob still attacks`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 10, maxHp = 10, damage = DamageRange(1, 1))
            fixture.mobs.upsert(mob)

            val statusEffects =
                fixture.buildStatusEffects(
                    StatusEffectDefinition(
                        id = StatusEffectId("stun"),
                        displayName = "Stun",
                        effectType = "stun",
                        durationMs = 5000,
                        stackBehavior = "none",
                    ),
                )
            val combat =
                fixture.buildCombat(
                    rng = Random(1),
                    minDamage = 5,
                    maxDamage = 5,
                    statusEffects = statusEffects,
                )

            val sid = SessionId(30L)
            fixture.players.loginOrFail(sid, "StunTest")
            combat.startCombat(sid, "rat")
            fixture.outbound.drainAll()

            // Apply stun to the player
            statusEffects.applyToPlayer(sid, StatusEffectId("stun"))

            fixture.tickCombat(combat)

            // Mob should still be at full HP (stunned player can't attack)
            assertEquals(10, mob.hp, "Stunned player should not damage mob")
            // Player should take damage from mob
            val player = fixture.players.get(sid)!!
            assertTrue(player.hp < player.maxHp, "Mob should still damage stunned player")
        }

    @Test
    fun `shield absorbs mob damage in combat`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 100, maxHp = 100, damage = DamageRange(5, 5))
            fixture.mobs.upsert(mob)

            val statusEffects =
                fixture.buildStatusEffects(
                    StatusEffectDefinition(
                        id = StatusEffectId("shield"),
                        displayName = "Shield",
                        effectType = "shield",
                        durationMs = 30000,
                        shieldAmount = 20,
                        stackBehavior = "none",
                    ),
                )
            val combat =
                fixture.buildCombat(
                    rng = Random(1),
                    minDamage = 1,
                    maxDamage = 1,
                    statusEffects = statusEffects,
                )

            val sid = SessionId(31L)
            fixture.players.loginOrFail(sid, "ShieldTest")
            combat.startCombat(sid, "rat")
            fixture.outbound.drainAll()

            // Apply shield to the player
            statusEffects.applyToPlayer(sid, StatusEffectId("shield"))

            fixture.tickCombat(combat)

            // Player should take no damage (shield absorbs the 5 damage)
            val player = fixture.players.get(sid)!!
            assertEquals(player.maxHp, player.hp, "Shield should absorb all mob damage")
        }

    @Test
    fun `stat buff adds to str damage bonus`() =
        runTest {
            val fixture = CombatTestFixture()
            // mob with 0 armor so we can see exact damage
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 50, maxHp = 50, damage = DamageRange(1, 1))
            fixture.mobs.upsert(mob)

            val statusEffects =
                fixture.buildStatusEffects(
                    StatusEffectDefinition(
                        id = StatusEffectId("buff"),
                        displayName = "Buff",
                        effectType = "stat_buff",
                        durationMs = 60000,
                        statMods = StatMap.of("STR" to 6),
                    ),
                )
            val combat =
                fixture.buildCombat(
                    rng = Random(1),
                    statusEffects = statusEffects,
                    // unarmed=3, +1 dmg per STR point above base, no level/variance scaling
                    bindings = deterministicMeleeBindings(unarmedAttackPower = 3).copy(
                        meleeDamageStat = "STR",
                        meleeStatMultiplier = 1.0,
                    ),
                )

            val sid = SessionId(32L)
            fixture.players.loginOrFail(sid, "BuffTest")

            // +6 STR buff with meleeStatMultiplier 1.0 → +6 bonus damage on top of unarmed 3
            statusEffects.applyToPlayer(sid, StatusEffectId("buff"))

            combat.startCombat(sid, "rat")
            fixture.outbound.drainAll()

            fixture.tickCombat(combat)

            // Damage = 3 (unarmed) + 6 (STR bonus) = 9. Mob HP 50 → 41.
            assertEquals(41, mob.hp, "STR buff should add bonus damage")
        }

    @Test
    fun `mob kill awards gold from gold range`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    fixture.roomId,
                    hp = 1,
                    maxHp = 1,
                    goldMin = 5L,
                    goldMax = 5L,
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)

            val sid = SessionId(30L)
            fixture.players.loginOrFail(sid, "GoldHunter")
            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            fixture.tickCombat(combat)

            val player = fixture.players.get(sid)
            assertNotNull(player)
            assertEquals(5L, player!!.gold)

            val messages =
                fixture.outbound
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
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    fixture.roomId,
                    hp = 1,
                    maxHp = 1,
                    goldMin = 0L,
                    goldMax = 0L,
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)

            val sid = SessionId(31L)
            fixture.players.loginOrFail(sid, "NoGold")
            val err = combat.startCombat(sid, "rat")
            assertNull(err)

            fixture.tickCombat(combat)

            val player = fixture.players.get(sid)
            assertNotNull(player)
            assertEquals(0L, player!!.gold)

            val messages =
                fixture.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendText>()
                    .filter { it.sessionId == sid }
                    .map { it.text }
            assertTrue(messages.none { it.contains("gold") && it.contains("find") }, "Expected no gold message, got: $messages")
        }

    @Test
    fun `player death invokes onPlayerDeath callback`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:ogre"),
                    "an ogre",
                    fixture.roomId,
                    hp = 100,
                    maxHp = 100,
                    damage = DamageRange(50, 50),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)

            val deathCallbackSessions = mutableListOf<SessionId>()
            combat.onPlayerDeath = { sid -> deathCallbackSessions.add(sid) }

            val sid = SessionId(30L)
            fixture.players.loginOrFail(sid, "Doomed")
            combat.startCombat(sid, "ogre")
            fixture.outbound.drainAll()

            fixture.tickCombat(combat)

            assertEquals(
                listOf(sid),
                deathCallbackSessions,
                "Expected onPlayerDeath callback to fire exactly once for the dying player",
            )
        }

    @Test
    fun `player death clears status effects via onPlayerDeath`() =
        runTest {
            val fixture = CombatTestFixture()
            val poison =
                StatusEffectDefinition(
                    id = StatusEffectId("poison"),
                    displayName = "Poison",
                    effectType = "damage",
                    durationMs = 60_000L,
                    tickIntervalMs = 5_000L,
                    tickMinValue = 5,
                    tickMaxValue = 5,
                )
            val statusEffects = fixture.buildStatusEffects(poison)
            val mob =
                MobState(
                    MobId("demo:ogre"),
                    "an ogre",
                    fixture.roomId,
                    hp = 100,
                    maxHp = 100,
                    damage = DamageRange(50, 50),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(
                rng = Random(1),
                minDamage = 1,
                maxDamage = 1,
                statusEffects = statusEffects,
            )

            val sid = SessionId(31L)
            fixture.players.loginOrFail(sid, "Poisoned")

            // Apply poison before combat
            statusEffects.applyToPlayer(sid, StatusEffectId("poison"))
            assertTrue(
                statusEffects.hasPlayerEffect(sid, "damage"),
                "Precondition: player should have damage effect",
            )

            // Wire onPlayerDeath to clear effects
            combat.onPlayerDeath = { s -> statusEffects.removeAllFromPlayer(s) }

            combat.startCombat(sid, "ogre")
            fixture.outbound.drainAll()

            fixture.tickCombat(combat)

            assertTrue(
                !statusEffects.hasPlayerEffect(sid, "damage"),
                "Expected damage effect to be cleared on death",
            )
        }

    @Test
    fun `autoloot disabled leaves mob drops in the room`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 1, maxHp = 1)
            fixture.mobs.upsert(mob)
            fixture.items.addMobItem(
                mob.id,
                ItemInstance(ItemId("demo:tail"), Item(keyword = "tail", displayName = "a rat tail")),
            )

            val combat = fixture.buildCombat(rng = Random(2))

            val sid = SessionId(101L)
            fixture.players.loginOrFail(sid, "Looter")
            fixture.players.setAutolootEnabled(sid, false)

            assertNull(combat.startCombat(sid, "rat"))
            fixture.tickCombat(combat)

            assertEquals(
                1,
                fixture.items.itemsInRoom(fixture.roomId).size,
                "Expected drop to remain in room when autoloot is off",
            )
            assertTrue(fixture.items.inventory(sid).isEmpty(), "Expected empty inventory")
        }

    @Test
    fun `autoloot enabled moves mob drops into killer inventory`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 1, maxHp = 1)
            fixture.mobs.upsert(mob)
            fixture.items.addMobItem(
                mob.id,
                ItemInstance(ItemId("demo:tail"), Item(keyword = "tail", displayName = "a rat tail")),
            )

            val combat = fixture.buildCombat(rng = Random(2))

            val sid = SessionId(102L)
            fixture.players.loginOrFail(sid, "Looter2")
            fixture.players.setAutolootEnabled(sid, true)

            assertNull(combat.startCombat(sid, "rat"))
            fixture.tickCombat(combat)

            assertTrue(
                fixture.items.itemsInRoom(fixture.roomId).isEmpty(),
                "Expected drops to be moved out of the room",
            )
            val inv = fixture.items.inventory(sid)
            assertEquals(1, inv.size, "Expected one item in inventory")
            assertEquals("demo:tail", inv.first().id.value)

            val messages =
                fixture.outbound
                    .drainAll()
                    .filterIsInstance<OutboundEvent.SendInfo>()
                    .map { it.text }
            assertTrue(
                messages.any { it.contains("You loot") && it.contains("a rat tail") },
                "Expected autoloot info message, got: $messages",
            )
        }

    @Test
    fun `autoloot fires onItemAutoLooted callback for each picked-up item`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 1, maxHp = 1)
            fixture.mobs.upsert(mob)
            fixture.items.addMobItem(
                mob.id,
                ItemInstance(ItemId("demo:tail"), Item(keyword = "tail", displayName = "a rat tail")),
            )
            fixture.items.addMobItem(
                mob.id,
                ItemInstance(ItemId("demo:ear"), Item(keyword = "ear", displayName = "a rat ear")),
            )

            val combat = fixture.buildCombat(rng = Random(2))
            val looted = mutableListOf<ItemInstance>()
            combat.onItemAutoLooted = { _, item -> looted += item }

            val sid = SessionId(201L)
            fixture.players.loginOrFail(sid, "Collector")
            fixture.players.setAutolootEnabled(sid, true)

            assertNull(combat.startCombat(sid, "rat"))
            fixture.tickCombat(combat)

            val ids = looted.map { it.id.value }.toSet()
            assertEquals(setOf("demo:tail", "demo:ear"), ids, "Expected both items to fire the callback")
        }

    @Test
    fun `Kill combat event carries autolooted item names`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 1, maxHp = 1)
            fixture.mobs.upsert(mob)
            fixture.items.addMobItem(
                mob.id,
                ItemInstance(ItemId("demo:tail"), Item(keyword = "tail", displayName = "a rat tail")),
            )
            fixture.items.addMobItem(
                mob.id,
                ItemInstance(ItemId("demo:ear"), Item(keyword = "ear", displayName = "a rat ear")),
            )

            val combat = fixture.buildCombat(rng = Random(2))

            val sid = SessionId(301L)
            fixture.players.loginOrFail(sid, "VictoryToaster")
            fixture.players.setAutolootEnabled(sid, true)

            val combatEvents = mutableListOf<CombatEvent>()
            combat.onCombatEvent = { _, event -> combatEvents += event }

            assertNull(combat.startCombat(sid, "rat"))
            fixture.tickCombat(combat)

            val kill = combatEvents.filterIsInstance<CombatEvent.Kill>().firstOrNull()
            assertNotNull(kill, "Expected a Kill combat event, got: $combatEvents")
            assertEquals(
                listOf("a rat tail", "a rat ear"),
                kill!!.lootedItems,
                "Kill event should carry autolooted item display names in pickup order",
            )
        }

    @Test
    fun `Kill combat event has empty lootedItems when autoloot is disabled`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 1, maxHp = 1)
            fixture.mobs.upsert(mob)
            fixture.items.addMobItem(
                mob.id,
                ItemInstance(ItemId("demo:tail"), Item(keyword = "tail", displayName = "a rat tail")),
            )

            val combat = fixture.buildCombat(rng = Random(2))

            val sid = SessionId(302L)
            fixture.players.loginOrFail(sid, "NoAutoloot")
            fixture.players.setAutolootEnabled(sid, false)

            val combatEvents = mutableListOf<CombatEvent>()
            combat.onCombatEvent = { _, event -> combatEvents += event }

            assertNull(combat.startCombat(sid, "rat"))
            fixture.tickCombat(combat)

            val kill = combatEvents.filterIsInstance<CombatEvent.Kill>().firstOrNull()
            assertNotNull(kill, "Expected a Kill combat event, got: $combatEvents")
            assertTrue(
                kill!!.lootedItems.isEmpty(),
                "Kill event should have empty lootedItems when autoloot is off, got: ${kill.lootedItems}",
            )
        }

    @Test
    fun `autoloot also picks up rolled loot-table drops`() =
        runTest {
            val fixture = CombatTestFixture()
            fixture.items.loadSpawns(
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
            val mob =
                MobState(
                    MobId("demo:wolf"),
                    "a wolf",
                    fixture.roomId,
                    hp = 1,
                    maxHp = 1,
                    drops = listOf(MobDrop(ItemId("demo:fang"), 1.0)),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(2))

            val sid = SessionId(103L)
            fixture.players.loginOrFail(sid, "Looter3")
            fixture.players.setAutolootEnabled(sid, true)

            assertNull(combat.startCombat(sid, "wolf"))
            fixture.tickCombat(combat)

            assertTrue(
                fixture.items.itemsInRoom(fixture.roomId).isEmpty(),
                "Expected rolled drop to be auto-looted",
            )
            val inv = fixture.items.inventory(sid)
            assertEquals(1, inv.size)
            assertEquals("demo:fang", inv.first().id.value)
        }

    @Test
    fun `player death moves to sanctum and records death zone`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:ogre"),
                    "an ogre",
                    fixture.roomId,
                    hp = 100,
                    maxHp = 100,
                    damage = DamageRange(50, 50),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)
            val sanctum = dev.ambon.domain.ids.RoomId("limbo:sanctum")
            combat.sanctumRoomLookup = { sanctum }
            combat.deathConfig = DeathConfig(
                sanctumRoom = sanctum.value,
                respawnHpFraction = 0.2,
                respawnManaFraction = 0.5,
            )

            val sid = SessionId(42L)
            fixture.players.loginOrFail(sid, "Doomed")
            val player = fixture.players.get(sid)!!
            player.maxHp = 50
            player.maxMana = 40
            val originalZone = player.roomId.zone

            combat.startCombat(sid, "ogre")
            fixture.outbound.drainAll()
            fixture.tickCombat(combat)

            assertEquals(sanctum, player.roomId, "Expected player to respawn in sanctum")
            assertEquals(originalZone, player.lastDeathZone, "Expected lastDeathZone recorded from pre-death room")
            assertEquals(10, player.hp, "Expected 20% of 50 maxHp = 10")
            assertEquals(20, player.mana, "Expected 50% of 40 maxMana = 20")
        }

    @Test
    fun `player death fires onPlayerRespawned so the web client re-renders the new room`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:ogre"),
                    "an ogre",
                    fixture.roomId,
                    hp = 100,
                    maxHp = 100,
                    damage = DamageRange(50, 50),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)
            val sanctum = dev.ambon.domain.ids.RoomId("limbo:sanctum")
            combat.sanctumRoomLookup = { sanctum }
            combat.deathConfig = DeathConfig(sanctumRoom = sanctum.value)

            val respawned = mutableListOf<Pair<SessionId, dev.ambon.domain.ids.RoomId>>()
            combat.onPlayerRespawned = { sid ->
                val p = fixture.players.get(sid)!!
                respawned += sid to p.roomId
            }

            val sid = SessionId(44L)
            fixture.players.loginOrFail(sid, "Reborn")

            combat.startCombat(sid, "ogre")
            fixture.outbound.drainAll()
            fixture.tickCombat(combat)

            assertEquals(
                listOf(sid to sanctum),
                respawned,
                "Expected onPlayerRespawned to fire exactly once with the sanctum room set",
            )
        }

    @Test
    fun `player death falls back to zone start when no sanctum configured`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:ogre"),
                    "an ogre",
                    fixture.roomId,
                    hp = 100,
                    maxHp = 100,
                    damage = DamageRange(50, 50),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)
            val zoneStart = dev.ambon.domain.ids.RoomId("zone:start")
            combat.zoneStartRoomLookup = { if (it == "zone") zoneStart else null }
            // No sanctum configured — falls back to zone start.

            val sid = SessionId(43L)
            fixture.players.loginOrFail(sid, "Fallbacked")
            val player = fixture.players.get(sid)!!

            combat.startCombat(sid, "ogre")
            fixture.outbound.drainAll()
            fixture.tickCombat(combat)

            assertEquals(zoneStart, player.roomId, "Expected fallback to zone start room")
            assertEquals("zone", player.lastDeathZone)
        }

    @Test
    fun `player death applies xp penalty when configured`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:ogre"),
                    "an ogre",
                    fixture.roomId,
                    hp = 100,
                    maxHp = 100,
                    damage = DamageRange(50, 50),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1), minDamage = 1, maxDamage = 1)
            combat.deathConfig = DeathConfig(xpPenaltyFraction = 0.1)

            val sid = SessionId(44L)
            fixture.players.loginOrFail(sid, "Penalised")
            val player = fixture.players.get(sid)!!
            player.xpTotal = 1000L

            combat.startCombat(sid, "ogre")
            fixture.outbound.drainAll()
            fixture.tickCombat(combat)

            assertEquals(900L, player.xpTotal, "Expected 10% xp penalty (1000 -> 900)")
        }

    @Test
    fun `onPlayerEnteredCombat fires when mob aggros a player`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:wolf"),
                    "a wolf",
                    fixture.roomId,
                    hp = 20,
                    maxHp = 20,
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1))

            val entered = mutableListOf<SessionId>()
            combat.onPlayerEnteredCombat = { sid -> entered.add(sid) }

            val sid = SessionId(50L)
            fixture.players.loginOrFail(sid, "Chatter")

            val started = combat.startMobCombat(mob.id, sid)
            assertTrue(started, "Expected startMobCombat to succeed")
            assertEquals(
                listOf(sid),
                entered,
                "Expected onPlayerEnteredCombat to fire exactly once for the aggro victim",
            )
        }

    @Test
    fun `onPlayerEnteredCombat fires when player initiates combat`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    fixture.roomId,
                    hp = 10,
                    maxHp = 10,
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1))

            val entered = mutableListOf<SessionId>()
            combat.onPlayerEnteredCombat = { sid -> entered.add(sid) }

            val sid = SessionId(51L)
            fixture.players.loginOrFail(sid, "Striker")

            assertNull(combat.startCombat(sid, "rat"))
            assertEquals(listOf(sid), entered)
        }

    @Test
    fun `consider returns easy rating against a weak mob`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:rat"),
                    "a rat",
                    fixture.roomId,
                    hp = 4,
                    maxHp = 4,
                    damage = DamageRange(1, 1),
                    level = 1,
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(minDamage = 4, maxDamage = 4)
            val sid = SessionId(70L)
            fixture.players.loginOrFail(sid, "Hero")

            val outcome = combat.consider(sid, "rat")
            assertTrue(outcome is ConsiderOutcome.Ok, "expected Ok, got $outcome")
            val result = (outcome as ConsiderOutcome.Ok).result
            assertEquals("a rat", result.mobName)
            assertTrue(
                result.rating in setOf(ConsiderRating.TRIVIAL, ConsiderRating.EASY),
                "expected easy/trivial rating, got ${result.rating} (win%=${result.winChancePct})",
            )
            assertTrue(result.winChancePct >= 75, "expected high win chance, got ${result.winChancePct}")
            assertEquals(1, result.hitsToKillMob)
        }

    @Test
    fun `consider returns suicidal rating against an overwhelming mob`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:dragon"),
                    "an ancient dragon",
                    fixture.roomId,
                    hp = 10_000,
                    maxHp = 10_000,
                    damage = DamageRange(500, 500),
                    level = 60,
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(minDamage = 1, maxDamage = 1)
            val sid = SessionId(71L)
            fixture.players.loginOrFail(sid, "Squire")

            val outcome = combat.consider(sid, "dragon")
            val result = (outcome as ConsiderOutcome.Ok).result
            assertEquals(ConsiderRating.SUICIDAL, result.rating)
            assertTrue(result.winChancePct < 10, "expected very low win chance, got ${result.winChancePct}")
        }

    @Test
    fun `wimpy does not fire above threshold`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:ogre"),
                    "an ogre",
                    fixture.roomId,
                    hp = 1000,
                    maxHp = 1000,
                    damage = DamageRange(100, 100),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1))
            val sid = SessionId(90L)
            fixture.players.loginOrFail(sid, "Wimper")
            val player = fixture.players.get(sid)!!
            player.maxHp = 200
            player.hp = 200
            player.wimpyThresholdPct = 25

            assertNull(combat.startCombat(sid, "ogre"))
            fixture.tickCombat(combat)

            // 100/200 = 50%, well above the 25% threshold — combat must continue.
            assertEquals(100, player.hp)
            assertTrue(combat.isInCombat(sid), "expected still in combat above threshold")
        }

    @Test
    fun `wimpy fires before death when threshold is generous`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:wolf"),
                    "a wolf",
                    fixture.roomId,
                    hp = 1000,
                    maxHp = 1000,
                    damage = DamageRange(20, 20),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1))
            val sid = SessionId(91L)
            fixture.players.loginOrFail(sid, "Cautious")
            val player = fixture.players.get(sid)!!
            player.maxHp = 100
            player.hp = 100
            player.wimpyThresholdPct = 50

            assertNull(combat.startCombat(sid, "wolf"))
            // Two ticks: 100 → 80 (80% > 50%) → 60 (60% > 50%). One more: 40 (≤50%) → wimpy fires.
            fixture.tickCombat(combat) // 80
            assertTrue(combat.isInCombat(sid))
            fixture.tickCombat(combat) // 60
            assertTrue(combat.isInCombat(sid))
            fixture.tickCombat(combat) // 40 → wimpy
            assertNull(combat.currentTarget(sid), "expected wimpy to break combat at threshold")
            assertTrue(player.hp > 0, "expected player still alive")
        }

    @Test
    fun `wimpy threshold compares via exact ratio not truncated percent`() =
        runTest {
            val fixture = CombatTestFixture()
            // Damage exactly 1 per tick at max=200, threshold=25.
            // 51/200 = 25.5% — must NOT trigger a 25% threshold (truncation bug would fire here).
            // 50/200 = 25.0% — must trigger.
            val mob =
                MobState(
                    MobId("demo:slow"),
                    "a slow slime",
                    fixture.roomId,
                    hp = 10_000,
                    maxHp = 10_000,
                    damage = DamageRange(1, 1),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1))
            val sid = SessionId(93L)
            fixture.players.loginOrFail(sid, "Picky")
            val player = fixture.players.get(sid)!!
            player.maxHp = 200
            player.hp = 52 // first tick → 51 = 25.5% (must not fire)
            player.wimpyThresholdPct = 25

            assertNull(combat.startCombat(sid, "slime"))
            fixture.tickCombat(combat)
            assertEquals(51, player.hp)
            assertTrue(combat.isInCombat(sid), "wimpy must not fire at 25.5% for a 25% threshold")

            fixture.tickCombat(combat) // 50 → 25.0% exactly
            assertEquals(50, player.hp)
            assertNull(combat.currentTarget(sid), "wimpy must fire at exactly the threshold")
        }

    @Test
    fun `wimpy of 0 never fires`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob =
                MobState(
                    MobId("demo:wolf"),
                    "a wolf",
                    fixture.roomId,
                    hp = 1000,
                    maxHp = 1000,
                    damage = DamageRange(10, 10),
                )
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(1))
            val sid = SessionId(92L)
            fixture.players.loginOrFail(sid, "Reckless")
            val player = fixture.players.get(sid)!!
            player.maxHp = 100
            player.hp = 100
            player.wimpyThresholdPct = 0

            assertNull(combat.startCombat(sid, "wolf"))
            repeat(5) { fixture.tickCombat(combat) }
            // Player at 50% — never fled since wimpy=0
            assertTrue(combat.isInCombat(sid), "expected still in combat with wimpy disabled")
        }

    @Test
    fun `consider rejects unknown target`() =
        runTest {
            val fixture = CombatTestFixture()
            val combat = fixture.buildCombat()
            val sid = SessionId(72L)
            fixture.players.loginOrFail(sid, "Wanderer")

            val outcome = combat.consider(sid, "ghost")
            assertTrue(outcome is ConsiderOutcome.Error)
            assertTrue((outcome as ConsiderOutcome.Error).message.contains("don't see"))
        }

    @Test
    fun `flee emits Flee combat event with mob name and forced=false`() =
        runTest {
            val fixture = CombatTestFixture()
            val mob = MobState(MobId("demo:rat"), "a rat", fixture.roomId, hp = 50, maxHp = 50)
            fixture.mobs.upsert(mob)

            val combat = fixture.buildCombat(rng = Random(7))
            val sid = SessionId(801L)
            fixture.players.loginOrFail(sid, "Runner")

            val combatEvents = mutableListOf<CombatEvent>()
            combat.onCombatEvent = { _, event -> combatEvents += event }

            assertNull(combat.startCombat(sid, "rat"))
            assertNull(combat.flee(sid))

            val flee = combatEvents.filterIsInstance<CombatEvent.Flee>().firstOrNull()
            assertNotNull(flee, "Expected a Flee combat event, got: $combatEvents")
            assertEquals("a rat", flee!!.targetName)
            assertEquals(false, flee.forced)
        }
}
