package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StaffImmunityTest {
    private fun staffPlayer() =
        PlayerState(
            sessionId = SessionId(1L),
            name = "Admin",
            roomId = RoomId("zone:a"),
            isStaff = true,
            hp = 100,
            maxHp = 100,
            mana = 50,
            maxMana = 50,
            gold = 10L,
        )

    private fun normalPlayer() =
        PlayerState(
            sessionId = SessionId(2L),
            name = "Normal",
            roomId = RoomId("zone:a"),
            isStaff = false,
            hp = 100,
            maxHp = 100,
            mana = 50,
            maxMana = 50,
            gold = 10L,
        )

    @Test
    fun `takeDamage is a no-op for staff`() {
        val p = staffPlayer()
        p.takeDamage(999)
        assertEquals(100, p.hp)
    }

    @Test
    fun `takeDamage reduces HP for normal player`() {
        val p = normalPlayer()
        p.takeDamage(30)
        assertEquals(70, p.hp)
    }

    @Test
    fun `spendMana is a no-op for staff`() {
        val p = staffPlayer()
        p.spendMana(999)
        assertEquals(50, p.mana)
    }

    @Test
    fun `spendMana reduces mana for normal player`() {
        val p = normalPlayer()
        p.spendMana(20)
        assertEquals(30, p.mana)
    }
}
