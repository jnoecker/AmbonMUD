package dev.ambon.engine

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobRole
import dev.ambon.domain.mob.MobState
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class MobRoleTest {
    @Test
    fun `parse accepts lowercase role names`() {
        assertEquals(MobRole.COMBAT, MobRole.parse("combat"))
        assertEquals(MobRole.VENDOR, MobRole.parse("VENDOR"))
        assertEquals(MobRole.QUEST_GIVER, MobRole.parse("quest_giver"))
        assertEquals(MobRole.QUEST_GIVER, MobRole.parse("quest-giver"))
        assertEquals(MobRole.DIALOG, MobRole.parse("Dialog"))
        assertEquals(MobRole.PROP, MobRole.parse("prop"))
    }

    @Test
    fun `parse defaults to combat when role is null or blank`() {
        assertEquals(MobRole.COMBAT, MobRole.parse(null))
        assertEquals(MobRole.COMBAT, MobRole.parse(""))
        assertEquals(MobRole.COMBAT, MobRole.parse("   "))
    }

    @Test
    fun `parse rejects unknown role names`() {
        assertThrows(IllegalArgumentException::class.java) { MobRole.parse("boss") }
    }

    @Test
    fun `isCombatant is true only for COMBAT`() {
        assertTrue(MobRole.COMBAT.isCombatant)
        assertFalse(MobRole.VENDOR.isCombatant)
        assertFalse(MobRole.QUEST_GIVER.isCombatant)
        assertFalse(MobRole.DIALOG.isCombatant)
        assertFalse(MobRole.PROP.isCombatant)
    }

    @Test
    fun `startCombat refuses a vendor mob`() =
        runTest {
            val fixture = CombatTestFixture()
            fixture.mobs.upsert(
                MobState(
                    id = MobId("town:shopkeep"),
                    name = "Quartermaster Quill",
                    roomId = fixture.roomId,
                    hp = 10,
                    maxHp = 10,
                    role = MobRole.VENDOR,
                ),
            )
            val combat = fixture.buildCombat(rng = Random(1))
            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Hero")

            val err = combat.startCombat(sid, "quill")
            assertNotNull(err)
            assertTrue(err!!.contains("shop", ignoreCase = true))
        }

    @Test
    fun `startCombat refuses a quest giver mob`() =
        runTest {
            val fixture = CombatTestFixture()
            fixture.mobs.upsert(
                MobState(
                    id = MobId("town:loremaster"),
                    name = "Elder Oak",
                    roomId = fixture.roomId,
                    hp = 10,
                    maxHp = 10,
                    role = MobRole.QUEST_GIVER,
                ),
            )
            val combat = fixture.buildCombat(rng = Random(1))
            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Hero")

            val err = combat.startCombat(sid, "oak")
            assertNotNull(err)
            assertTrue(err!!.contains("quarrel", ignoreCase = true) || err.contains("work", ignoreCase = true))
        }

    @Test
    fun `startCombat refuses a prop`() =
        runTest {
            val fixture = CombatTestFixture()
            fixture.mobs.upsert(
                MobState(
                    id = MobId("town:statue"),
                    name = "a weathered statue",
                    roomId = fixture.roomId,
                    hp = 1,
                    maxHp = 1,
                    role = MobRole.PROP,
                ),
            )
            val combat = fixture.buildCombat(rng = Random(1))
            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Hero")

            val err = combat.startCombat(sid, "statue")
            assertNotNull(err)
            assertTrue(err!!.contains("not something you can attack", ignoreCase = true))
        }

    @Test
    fun `startCombat accepts a combat mob`() =
        runTest {
            val fixture = CombatTestFixture()
            fixture.mobs.upsert(
                MobState(
                    id = MobId("demo:rat"),
                    name = "a rat",
                    roomId = fixture.roomId,
                    hp = 10,
                    maxHp = 10,
                    role = MobRole.COMBAT,
                ),
            )
            val combat = fixture.buildCombat(rng = Random(1))
            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Hero")

            assertNull(combat.startCombat(sid, "rat"))
        }

    @Test
    fun `startMobCombat refuses to initiate for a non-combat mob`() =
        runTest {
            val fixture = CombatTestFixture()
            val mobId = MobId("town:shopkeep")
            fixture.mobs.upsert(
                MobState(
                    id = mobId,
                    name = "Quartermaster Quill",
                    roomId = fixture.roomId,
                    hp = 10,
                    maxHp = 10,
                    role = MobRole.VENDOR,
                ),
            )
            val combat = fixture.buildCombat(rng = Random(1))
            val sid = SessionId(1L)
            fixture.players.loginOrFail(sid, "Hero")

            assertFalse(combat.startMobCombat(mobId, sid))
        }
}
