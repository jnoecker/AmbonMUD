package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.test.CombatTestFixture
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import dev.ambon.test.textMessages
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

@OptIn(ExperimentalCoroutinesApi::class)
class PvpCombatTest {
    private val roomId = RoomId("pvp_zone:arena")
    private val startRoomId = RoomId("pvp_zone:start")

    @Test
    fun `startPvpCombat registers both players as combatants`() = runTest {
        val fixture = CombatTestFixture(roomId = roomId)
        val combat = fixture.buildCombat(rng = Random(1))

        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        fixture.players.loginOrFail(sid1, "Player1")
        fixture.players.loginOrFail(sid2, "Player2")

        val err = combat.startPvpCombat(sid1, sid2)
        assertNull(err)

        assertTrue(combat.isInPvpCombat(sid1))
        assertTrue(combat.isInPvpCombat(sid2))
        assertTrue(combat.isInCombat(sid1))
        assertTrue(combat.isInCombat(sid2))
        assertEquals(sid2, combat.getPvpOpponent(sid1))
        assertEquals(sid1, combat.getPvpOpponent(sid2))
    }

    @Test
    fun `cannot attack yourself`() = runTest {
        val fixture = CombatTestFixture(roomId = roomId)
        val combat = fixture.buildCombat()

        val sid = SessionId(1L)
        fixture.players.loginOrFail(sid, "Player1")

        val err = combat.startPvpCombat(sid, sid)
        assertNotNull(err)
        assertTrue(err!!.contains("yourself"))
    }

    @Test
    fun `cannot attack staff members`() = runTest {
        val fixture = CombatTestFixture(roomId = roomId)
        val combat = fixture.buildCombat()

        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        fixture.players.loginOrFail(sid1, "Player1")
        fixture.players.loginOrFail(sid2, "StaffPlayer")
        fixture.players.get(sid2)!!.isStaff = true

        val err = combat.startPvpCombat(sid1, sid2)
        assertNotNull(err)
        assertTrue(err!!.contains("staff"))
    }

    @Test
    fun `cannot attack player already in PvP combat`() = runTest {
        val fixture = CombatTestFixture(roomId = roomId)
        val combat = fixture.buildCombat()

        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        fixture.players.loginOrFail(sid1, "Player1")
        fixture.players.loginOrFail(sid2, "Player2")

        combat.startPvpCombat(sid1, sid2)
        val err = combat.startPvpCombat(sid1, sid2)
        assertNotNull(err)
        assertTrue(err!!.contains("already"))
    }

    @Test
    fun `pvp tick deals damage to target`() = runTest {
        val fixture = CombatTestFixture(roomId = roomId)
        val combat = fixture.buildCombat(rng = Random(42), minDamage = 2, maxDamage = 2)

        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        fixture.players.loginOrFail(sid1, "Player1")
        fixture.players.loginOrFail(sid2, "Player2")

        val p2 = fixture.players.get(sid2)!!
        val initialHp2 = p2.hp

        combat.startPvpCombat(sid1, sid2)
        fixture.outbound.drainAll()

        fixture.clock.advance(1_000L)
        combat.tickPvpCombat()

        assertTrue(p2.hp < initialHp2, "Expected target to take damage")
    }

    @Test
    fun `pvp death respawns loser at zone start with full hp`() = runTest {
        val fixture = CombatTestFixture(roomId = roomId)
        val combat = fixture.buildCombat(rng = Random(1), minDamage = 100, maxDamage = 100)
        combat.zoneStartRoomLookup = { zone ->
            if (zone == "pvp_zone") startRoomId else null
        }

        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        fixture.players.loginOrFail(sid1, "Player1")
        fixture.players.loginOrFail(sid2, "Player2")

        combat.startPvpCombat(sid1, sid2)
        fixture.outbound.drainAll()

        fixture.clock.advance(1_000L)
        combat.tickPvpCombat()

        val p2 = fixture.players.get(sid2)!!

        assertEquals(startRoomId, p2.roomId)
        assertEquals(p2.maxHp, p2.hp)
        assertEquals(p2.maxMana, p2.mana)

        assertFalse(combat.isInPvpCombat(sid1))
        assertFalse(combat.isInPvpCombat(sid2))
    }

    @Test
    fun `pvp kill increments kill and death counters`() = runTest {
        val fixture = CombatTestFixture(roomId = roomId)
        val combat = fixture.buildCombat(rng = Random(1), minDamage = 100, maxDamage = 100)
        combat.zoneStartRoomLookup = { zone ->
            if (zone == "pvp_zone") startRoomId else null
        }

        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        fixture.players.loginOrFail(sid1, "Attacker")
        fixture.players.loginOrFail(sid2, "Victim")

        combat.startPvpCombat(sid1, sid2)
        fixture.outbound.drainAll()

        fixture.clock.advance(1_000L)
        combat.tickPvpCombat()

        val attacker = fixture.players.get(sid1)!!
        val victim = fixture.players.get(sid2)!!

        assertEquals(1, attacker.pvpKills)
        assertEquals(1, victim.pvpDeaths)
    }

    @Test
    fun `flee from PvP combat ends combat`() = runTest {
        val fixture = CombatTestFixture(roomId = roomId)
        val combat = fixture.buildCombat()

        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        fixture.players.loginOrFail(sid1, "Player1")
        fixture.players.loginOrFail(sid2, "Player2")

        combat.startPvpCombat(sid1, sid2)
        fixture.outbound.drainAll()

        val err = combat.fleePvp(sid1)
        assertNull(err)

        assertFalse(combat.isInPvpCombat(sid1))
        assertFalse(combat.isInPvpCombat(sid2))

        val messages = fixture.outbound.drainAll().textMessages(sid1)
        assertTrue(messages.any { it.contains("flee") })
    }

    @Test
    fun `disconnect during PvP cleans up state`() = runTest {
        val fixture = CombatTestFixture(roomId = roomId)
        val combat = fixture.buildCombat()

        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        fixture.players.loginOrFail(sid1, "Player1")
        fixture.players.loginOrFail(sid2, "Player2")

        combat.startPvpCombat(sid1, sid2)

        combat.onPlayerDisconnected(sid1)

        assertFalse(combat.isInPvpCombat(sid1))
        assertFalse(combat.isInPvpCombat(sid2))
    }

    @Test
    fun `pvp tick does not resolve before interval`() = runTest {
        val fixture = CombatTestFixture(roomId = roomId)
        val combat = fixture.buildCombat(rng = Random(1))

        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        fixture.players.loginOrFail(sid1, "Player1")
        fixture.players.loginOrFail(sid2, "Player2")

        combat.startPvpCombat(sid1, sid2)
        fixture.outbound.drainAll()

        combat.tickPvpCombat()

        val p1 = fixture.players.get(sid1)!!
        val p2 = fixture.players.get(sid2)!!
        assertEquals(p1.maxHp, p1.hp)
        assertEquals(p2.maxHp, p2.hp)
    }

    @Test
    fun `death messages sent to both players`() = runTest {
        val fixture = CombatTestFixture(roomId = roomId)
        val combat = fixture.buildCombat(rng = Random(1), minDamage = 100, maxDamage = 100)
        combat.zoneStartRoomLookup = { zone ->
            if (zone == "pvp_zone") startRoomId else null
        }

        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)
        fixture.players.loginOrFail(sid1, "Killer")
        fixture.players.loginOrFail(sid2, "Victim")

        combat.startPvpCombat(sid1, sid2)
        fixture.outbound.drainAll()

        fixture.clock.advance(1_000L)
        combat.tickPvpCombat()

        val allEvents = fixture.outbound.drainAll()
        val killerTexts = allEvents.textMessages(sid1)
        val victimTexts = allEvents.textMessages(sid2)

        assertTrue(killerTexts.any { it.contains("defeated") })
        assertTrue(victimTexts.any { it.contains("slain") })
    }
}
