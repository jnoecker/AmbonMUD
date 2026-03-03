package dev.ambon.engine.commands

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.engine.LoginResult
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NamesTellGossipTest {
    @Test
    fun `name sets and who reflects new name`() =
        runTest {
            val h = CommandRouterHarness.create()
            val a = SessionId(1)
            h.players.loginOrFail(a, "Alice")
            h.router.handle(a, Command.Who)

            val outs = h.outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == a && it.text.contains("Alice") && it.text.contains("Online:") },
                "Expected who to show Alice. got=$outs",
            )
        }

    @Test
    fun `name must be unique case-insensitively`() =
        runTest {
            val h = CommandRouterHarness.create()
            val a = SessionId(1)
            val b = SessionId(2)
            h.players.loginOrFail(a, "Alice")

            val res = h.players.login(b, "alice", "password")
            assertTrue(res is LoginResult.Takeover, "Expected Takeover for duplicate login with correct password. got=$res")
        }

    @Test
    fun `tell delivers to target only`() =
        runTest {
            val h = CommandRouterHarness.create()
            val a = SessionId(1)
            val b = SessionId(2)
            val c = SessionId(3)
            h.players.loginOrFail(a, "Alice")
            h.players.loginOrFail(b, "Bob")
            h.players.loginOrFail(c, "Eve")
            h.outbound.drainAll()

            h.router.handle(a, Command.Tell("Bob", "hi there"))
            val outs = h.outbound.drainAll()

            assertTrue(
                outs.any {
                    it is OutboundEvent.SendText &&
                        it.sessionId == a &&
                        it.text.contains(
                            "You tell",
                        ) &&
                        it.text.contains("hi there")
                },
                "Sender should see confirmation. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text.contains("Alice tells you: hi there") },
                "Target should receive tell. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.sessionId == c && it.text.contains("hi there") },
                "Non-target should not receive tell. got=$outs",
            )
        }

    @Test
    fun `gossip broadcasts to all connected`() =
        runTest {
            val h = CommandRouterHarness.create()
            val a = SessionId(1)
            val b = SessionId(2)
            h.players.loginOrFail(a, "Alice")
            h.players.loginOrFail(b, "Bob")
            h.outbound.drainAll()

            h.router.handle(a, Command.Gossip("hello everyone"))
            val outs = h.outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == a && it.text.contains("You gossip: hello everyone") },
                "Sender should see self message. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text.contains("[GOSSIP] Alice: hello everyone") },
                "Other should see gossip broadcast. got=$outs",
            )
        }

    @Test
    fun `tell across rooms still works`() =
        runTest {
            val h = CommandRouterHarness.create()
            val a = SessionId(1)
            val b = SessionId(2)
            h.players.loginOrFail(a, "Alice")
            h.players.loginOrFail(b, "Bob")
            h.outbound.drainAll()

            // Move Bob north so he is not in Alice's room
            h.router.handle(b, Command.Move(Direction.NORTH))
            h.outbound.drainAll()

            h.router.handle(a, Command.Tell("Bob", "still works"))
            val outs = h.outbound.drainAll()

            assertTrue(outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text.contains("still works") })
        }
}
