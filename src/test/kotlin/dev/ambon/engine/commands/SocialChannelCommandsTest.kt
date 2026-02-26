package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.drainAll
import dev.ambon.test.loginOrFail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SocialChannelCommandsTest {
    private fun buildFixture(): Triple<CommandRouter, PlayerRegistry, LocalOutboundBus> {
        val world = WorldLoader.loadFromResource("world/test_world.yaml")
        val items = ItemRegistry()
        val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val router = buildTestRouter(world, players, mobs, items, CombatSystem(players, mobs, items, outbound), outbound)
        return Triple(router, players, outbound)
    }

    // ─── Whisper ────────────────────────────────────────────────────────────────

    @Test
    fun `whisper delivers private message to target in same room`() =
        runTest {
            val (router, players, outbound) = buildFixture()
            val a = SessionId(1)
            val b = SessionId(2)
            players.loginOrFail(a, "Alice")
            players.loginOrFail(b, "Bob")

            router.handle(a, Command.Whisper("Bob", "hey secret"))
            val outs = outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == a && it.text == "You whisper to Bob: hey secret" },
                "Sender should see confirmation. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text == "Alice whispers to you: hey secret" },
                "Target should receive whisper. got=$outs",
            )
        }

    @Test
    fun `whisper fails when target is not in the same room`() =
        runTest {
            val (router, players, outbound) = buildFixture()
            val a = SessionId(1)
            val b = SessionId(2)
            players.loginOrFail(a, "Alice")
            players.loginOrFail(b, "Bob")

            // Move Bob north into a different room
            router.handle(b, Command.Move(Direction.NORTH))
            outbound.drainAll()

            router.handle(a, Command.Whisper("Bob", "hey"))
            val outs = outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == a && it.text.contains("not here") },
                "Sender should get 'not here' error. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b },
                "Bob should not receive anything. got=$outs",
            )
        }

    @Test
    fun `whisper fails when target player does not exist`() =
        runTest {
            val (router, players, outbound) = buildFixture()
            val a = SessionId(1)
            players.loginOrFail(a, "Alice")

            router.handle(a, Command.Whisper("Nobody", "hello"))
            val outs = outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == a },
                "Should get error for unknown target. got=$outs",
            )
        }

    @Test
    fun `whisper to self shows self message`() =
        runTest {
            val (router, players, outbound) = buildFixture()
            val a = SessionId(1)
            players.loginOrFail(a, "Alice")

            router.handle(a, Command.Whisper("Alice", "talking to myself"))
            val outs = outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.sessionId == a && it.text.contains("yourself") },
                "Self-whisper should produce info message. got=$outs",
            )
        }

    // ─── Shout ──────────────────────────────────────────────────────────────────

    @Test
    fun `shout broadcasts to all players in same zone`() =
        runTest {
            val (router, players, outbound) = buildFixture()
            val a = SessionId(1)
            val b = SessionId(2)
            players.loginOrFail(a, "Alice")
            players.loginOrFail(b, "Bob")

            // Move Bob to a different room but same zone
            router.handle(b, Command.Move(Direction.NORTH))
            outbound.drainAll()

            router.handle(a, Command.Shout("zone shout"))
            val outs = outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == a && it.text == "You shout: zone shout" },
                "Sender should see shout confirmation. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text.contains("zone shout") },
                "Bob in same zone should hear shout. got=$outs",
            )
        }

    @Test
    fun `shout is prefixed with SHOUT for recipients`() =
        runTest {
            val (router, players, outbound) = buildFixture()
            val a = SessionId(1)
            val b = SessionId(2)
            players.loginOrFail(a, "Alice")
            players.loginOrFail(b, "Bob")
            outbound.drainAll()

            router.handle(a, Command.Shout("hello zone"))
            val outs = outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text.startsWith("[SHOUT]") },
                "Recipient shout message should have [SHOUT] prefix. got=$outs",
            )
        }

    // ─── OOC ────────────────────────────────────────────────────────────────────

    @Test
    fun `ooc broadcasts to all players globally`() =
        runTest {
            val (router, players, outbound) = buildFixture()
            val a = SessionId(1)
            val b = SessionId(2)
            players.loginOrFail(a, "Alice")
            players.loginOrFail(b, "Bob")

            // Move Bob to a different room
            router.handle(b, Command.Move(Direction.NORTH))
            outbound.drainAll()

            router.handle(a, Command.Ooc("hello everyone"))
            val outs = outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == a && it.text.contains("You say OOC") },
                "Sender should see OOC confirmation. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text.contains("hello everyone") },
                "Bob in another room should receive OOC. got=$outs",
            )
        }

    @Test
    fun `ooc message to recipients is prefixed with OOC`() =
        runTest {
            val (router, players, outbound) = buildFixture()
            val a = SessionId(1)
            val b = SessionId(2)
            players.loginOrFail(a, "Alice")
            players.loginOrFail(b, "Bob")
            outbound.drainAll()

            router.handle(a, Command.Ooc("out of character"))
            val outs = outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text.startsWith("[OOC]") },
                "Recipient OOC message should have [OOC] prefix. got=$outs",
            )
        }

    // ─── Pose ───────────────────────────────────────────────────────────────────

    @Test
    fun `pose sends raw message to all in room without name prefix`() =
        runTest {
            val (router, players, outbound) = buildFixture()
            val a = SessionId(1)
            val b = SessionId(2)
            players.loginOrFail(a, "Alice")
            players.loginOrFail(b, "Bob")
            outbound.drainAll()

            router.handle(a, Command.Pose("Alice coughs loudly."))
            val outs = outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == a && it.text == "Alice coughs loudly." },
                "Sender should see pose text as-is. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b && it.text == "Alice coughs loudly." },
                "Others in room should see pose text as-is. got=$outs",
            )
        }

    @Test
    fun `pose rejected when player name absent from message`() =
        runTest {
            val (router, players, outbound) = buildFixture()
            val a = SessionId(1)
            players.loginOrFail(a, "Alice")
            outbound.drainAll()

            router.handle(a, Command.Pose("The old man coughs loudly."))
            val outs = outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.sessionId == a },
                "Should get an error when name is absent. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.sessionId == a },
                "Pose text should not be sent when rejected. got=$outs",
            )
        }

    @Test
    fun `pose does not send to players in other rooms`() =
        runTest {
            val (router, players, outbound) = buildFixture()
            val a = SessionId(1)
            val b = SessionId(2)
            players.loginOrFail(a, "Alice")
            players.loginOrFail(b, "Bob")

            // Move Bob away
            router.handle(b, Command.Move(Direction.NORTH))
            outbound.drainAll()

            router.handle(a, Command.Pose("Alice dances alone."))
            val outs = outbound.drainAll()

            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.sessionId == a && it.text == "Alice dances alone." },
                "Sender should see pose. got=$outs",
            )
            assertFalse(
                outs.any { it is OutboundEvent.SendText && it.sessionId == b },
                "Bob in another room should not receive pose. got=$outs",
            )
        }

    // ─── Parser ─────────────────────────────────────────────────────────────────

    @Test
    fun `parser parses whisper and wh alias`() {
        assert(CommandParser.parse("whisper Bob hello there") == Command.Whisper("Bob", "hello there"))
        assert(CommandParser.parse("wh Bob hello there") == Command.Whisper("Bob", "hello there"))
    }

    @Test
    fun `parser returns Invalid for incomplete whisper`() {
        assertTrue(CommandParser.parse("whisper") is Command.Invalid)
        assertTrue(CommandParser.parse("whisper Bob") is Command.Invalid)
        assertTrue(CommandParser.parse("wh Bob") is Command.Invalid)
    }

    @Test
    fun `parser parses shout and sh alias`() {
        assert(CommandParser.parse("shout hello zone") == Command.Shout("hello zone"))
        assert(CommandParser.parse("sh hello zone") == Command.Shout("hello zone"))
    }

    @Test
    fun `parser returns Invalid for empty shout`() {
        assertTrue(CommandParser.parse("shout") is Command.Invalid)
        assertTrue(CommandParser.parse("shout   ") is Command.Invalid)
    }

    @Test
    fun `parser parses ooc`() {
        assert(CommandParser.parse("ooc just chatting") == Command.Ooc("just chatting"))
    }

    @Test
    fun `parser returns Invalid for empty ooc`() {
        assertTrue(CommandParser.parse("ooc") is Command.Invalid)
        assertTrue(CommandParser.parse("ooc   ") is Command.Invalid)
    }

    @Test
    fun `parser parses pose and po alias`() {
        assert(CommandParser.parse("pose The cat yawns.") == Command.Pose("The cat yawns."))
        assert(CommandParser.parse("po The cat yawns.") == Command.Pose("The cat yawns."))
    }

    @Test
    fun `parser returns Invalid for empty pose`() {
        assertTrue(CommandParser.parse("pose") is Command.Invalid)
        assertTrue(CommandParser.parse("pose   ") is Command.Invalid)
    }
}
