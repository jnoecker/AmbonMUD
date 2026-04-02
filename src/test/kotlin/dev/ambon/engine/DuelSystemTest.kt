package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class DuelSystemTest {
    private val clock = MutableClock(1000L)
    private val sid1 = SessionId(1L)
    private val sid2 = SessionId(2L)
    private val sid3 = SessionId(3L)

    private lateinit var duel: DuelSystem

    @BeforeEach
    fun setUp() {
        duel = DuelSystem(clock = clock, challengeTimeoutMs = 30_000L)
    }

    @Nested
    inner class Challenges {
        @Test
        fun `challenge creates pending challenge`() {
            assertNull(duel.challenge(sid1, sid2))
            assertNotNull(duel.getPendingChallenge(sid2))
        }

        @Test
        fun `cannot challenge yourself`() {
            val error = duel.challenge(sid1, sid1)
            assertEquals("You cannot duel yourself.", error)
        }

        @Test
        fun `cannot challenge while already in duel`() {
            duel.challenge(sid1, sid2)
            duel.accept(sid2)
            val error = duel.challenge(sid1, sid3)
            assertEquals("You are already in a duel.", error)
        }

        @Test
        fun `cannot challenge player already in duel`() {
            duel.challenge(sid1, sid2)
            duel.accept(sid2)
            val error = duel.challenge(sid3, sid2)
            assertEquals("That player is already in a duel.", error)
        }

        @Test
        fun `duplicate challenge returns error`() {
            duel.challenge(sid1, sid2)
            val error = duel.challenge(sid1, sid2)
            assertEquals("You already challenged that player.", error)
        }

        @Test
        fun `challenge expires after timeout`() {
            duel.challenge(sid1, sid2)
            clock.advance(31_000L)
            val result = duel.accept(sid2)
            assertNull(result)
        }
    }

    @Nested
    inner class AcceptDecline {
        @Test
        fun `accept starts active duel`() {
            duel.challenge(sid1, sid2)
            val activeDuel = duel.accept(sid2)
            assertNotNull(activeDuel)
            assertTrue(duel.isInDuel(sid1))
            assertTrue(duel.isInDuel(sid2))
            assertEquals(sid2, duel.getOpponent(sid1))
            assertEquals(sid1, duel.getOpponent(sid2))
        }

        @Test
        fun `accept with no pending returns null`() {
            assertNull(duel.accept(sid2))
        }

        @Test
        fun `decline removes pending challenge`() {
            duel.challenge(sid1, sid2)
            val declined = duel.decline(sid2)
            assertNotNull(declined)
            assertNull(duel.getPendingChallenge(sid2))
        }
    }

    @Nested
    inner class ActiveDuels {
        @Test
        fun `endDuel clears both players`() {
            duel.challenge(sid1, sid2)
            duel.accept(sid2)

            val ended = duel.endDuel(sid1)
            assertNotNull(ended)
            assertFalse(duel.isInDuel(sid1))
            assertFalse(duel.isInDuel(sid2))
        }

        @Test
        fun `activeDuels returns deduplicated list`() {
            duel.challenge(sid1, sid2)
            duel.accept(sid2)
            assertEquals(1, duel.activeDuels().size)
        }

        @Test
        fun `onPlayerDisconnect ends duel`() {
            duel.challenge(sid1, sid2)
            duel.accept(sid2)

            val ended = duel.onPlayerDisconnect(sid1)
            assertNotNull(ended)
            assertFalse(duel.isInDuel(sid1))
            assertFalse(duel.isInDuel(sid2))
        }

        @Test
        fun `onPlayerDisconnect removes pending challenges`() {
            duel.challenge(sid1, sid2)
            duel.onPlayerDisconnect(sid1)
            assertNull(duel.getPendingChallenge(sid2))
        }
    }
}
