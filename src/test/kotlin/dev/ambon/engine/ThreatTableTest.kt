package dev.ambon.engine

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ThreatTableTest {
    @Test
    fun `add threat and verify top threat`() {
        val tt = ThreatTable()
        val mob = MobId("zone:rat")
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)

        tt.addThreat(mob, sid1, 10.0)
        tt.addThreat(mob, sid2, 20.0)

        assertEquals(sid2, tt.topThreat(mob))
    }

    @Test
    fun `cumulative threat`() {
        val tt = ThreatTable()
        val mob = MobId("zone:rat")
        val sid1 = SessionId(1L)

        tt.addThreat(mob, sid1, 5.0)
        tt.addThreat(mob, sid1, 10.0)

        assertEquals(15.0, tt.getThreat(mob, sid1))
    }

    @Test
    fun `remove player clears their threat`() {
        val tt = ThreatTable()
        val mob = MobId("zone:rat")
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)

        tt.addThreat(mob, sid1, 10.0)
        tt.addThreat(mob, sid2, 5.0)

        tt.removePlayer(sid1)

        assertEquals(sid2, tt.topThreat(mob))
        assertFalse(tt.hasThreat(mob, sid1))
    }

    @Test
    fun `remove mob clears its table`() {
        val tt = ThreatTable()
        val mob = MobId("zone:rat")
        val sid1 = SessionId(1L)

        tt.addThreat(mob, sid1, 10.0)
        tt.removeMob(mob)

        assertNull(tt.topThreat(mob))
        assertFalse(tt.hasMobEntry(mob))
    }

    @Test
    fun `mobs threatened by player`() {
        val tt = ThreatTable()
        val mob1 = MobId("zone:rat1")
        val mob2 = MobId("zone:rat2")
        val sid1 = SessionId(1L)

        tt.addThreat(mob1, sid1, 10.0)
        tt.addThreat(mob2, sid1, 5.0)

        assertEquals(setOf(mob1, mob2), tt.mobsThreatenedBy(sid1))
    }

    @Test
    fun `players threatening mob`() {
        val tt = ThreatTable()
        val mob = MobId("zone:rat")
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)

        tt.addThreat(mob, sid1, 10.0)
        tt.addThreat(mob, sid2, 5.0)

        assertEquals(setOf(sid1, sid2), tt.playersThreateningMob(mob))
    }

    @Test
    fun `remap session transfers threat`() {
        val tt = ThreatTable()
        val mob = MobId("zone:rat")
        val oldSid = SessionId(1L)
        val newSid = SessionId(2L)

        tt.addThreat(mob, oldSid, 10.0)
        tt.remapSession(oldSid, newSid)

        assertFalse(tt.hasThreat(mob, oldSid))
        assertEquals(10.0, tt.getThreat(mob, newSid))
    }

    @Test
    fun `topThreatInRoom filters by room`() {
        val tt = ThreatTable()
        val mob = MobId("zone:rat")
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)

        tt.addThreat(mob, sid1, 100.0) // Highest threat but not in room
        tt.addThreat(mob, sid2, 5.0) // Lower threat but in room

        val result = tt.topThreatInRoom(mob) { it == sid2 }
        assertEquals(sid2, result)
    }

    @Test
    fun `set threat overwrites previous value`() {
        val tt = ThreatTable()
        val mob = MobId("zone:rat")
        val sid1 = SessionId(1L)

        tt.addThreat(mob, sid1, 10.0)
        tt.setThreat(mob, sid1, 50.0)

        assertEquals(50.0, tt.getThreat(mob, sid1))
    }

    @Test
    fun `maxThreatValue returns highest value`() {
        val tt = ThreatTable()
        val mob = MobId("zone:rat")
        val sid1 = SessionId(1L)
        val sid2 = SessionId(2L)

        tt.addThreat(mob, sid1, 10.0)
        tt.addThreat(mob, sid2, 30.0)

        assertEquals(30.0, tt.maxThreatValue(mob))
    }

    @Test
    fun `removing last player from mob cleans up mob entry`() {
        val tt = ThreatTable()
        val mob = MobId("zone:rat")
        val sid1 = SessionId(1L)

        tt.addThreat(mob, sid1, 10.0)
        tt.removePlayer(sid1)

        assertFalse(tt.hasMobEntry(mob))
    }
}
