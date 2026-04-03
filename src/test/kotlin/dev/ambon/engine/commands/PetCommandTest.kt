package dev.ambon.engine.commands

import dev.ambon.config.PetConfig
import dev.ambon.config.PetTemplateConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PetSystem
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.test.CommandRouterHarness
import dev.ambon.test.MutableClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PetCommandTest {
    // ── Parser tests ─────────────────────────────────────────────────────

    @Nested
    inner class Parser {
        @Test
        fun `pet alone parses to PetStatus`() {
            assertEquals(Command.PetStatus, CommandParser.parse("pet"))
        }

        @Test
        fun `pet status parses to PetStatus`() {
            assertEquals(Command.PetStatus, CommandParser.parse("pet status"))
        }

        @Test
        fun `pet dismiss parses to PetDismiss`() {
            assertEquals(Command.PetDismiss, CommandParser.parse("pet dismiss"))
        }

        @Test
        fun `pet unsummon parses to PetDismiss`() {
            assertEquals(Command.PetDismiss, CommandParser.parse("pet unsummon"))
        }

        @Test
        fun `pet release parses to PetDismiss`() {
            assertEquals(Command.PetDismiss, CommandParser.parse("pet release"))
        }

        @Test
        fun `pet name with arg parses to PetName`() {
            val result = CommandParser.parse("pet name Sparky")
            assertTrue(result is Command.PetName)
            assertEquals("Sparky", (result as Command.PetName).newName)
        }

        @Test
        fun `pet name without arg returns Invalid`() {
            val result = CommandParser.parse("pet name")
            assertTrue(result is Command.Invalid)
        }

        @Test
        fun `pet unknown subcommand defaults to PetStatus`() {
            assertEquals(Command.PetStatus, CommandParser.parse("pet info"))
        }
    }

    // ── Router tests ─────────────────────────────────────────────────────

    private val petConfig = PetConfig(
        definitions = mapOf(
            "fire_familiar" to PetTemplateConfig(
                name = "a fire familiar",
                description = "A small elemental of living flame.",
                hp = 20,
                minDamage = 2,
                maxDamage = 5,
                armor = 1,
            ),
        ),
    )

    private fun harness(): Triple<CommandRouterHarness, PetSystem, MobRegistry> {
        val mobs = MobRegistry()
        val clock = MutableClock(1000L)
        val petSystem = PetSystem(config = petConfig, mobs = mobs, clock = clock)
        val h = CommandRouterHarness.create(mobs = mobs, petSystem = petSystem)
        return Triple(h, petSystem, mobs)
    }

    @Nested
    inner class Router {
        @Test
        fun `pet status with no pet shows summon hint`() = runTest {
            val (h, _, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.PetStatus)

            val texts = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(texts.any { it.contains("no active pet", ignoreCase = true) }, "got=$texts")
        }

        @Test
        fun `pet status shows pet info when pet is active`() = runTest {
            val (h, petSystem, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val player = h.players.get(sid)!!
            petSystem.summon(sid, "fire_familiar", player.roomId, player.level)
            h.drain()

            h.router.handle(sid, Command.PetStatus)

            val texts = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(texts.any { it.contains("a fire familiar") }, "Expected pet name in output. got=$texts")
            assertTrue(texts.any { it.contains("HP:") }, "Expected HP line. got=$texts")
        }

        @Test
        fun `pet dismiss removes pet and sends info`() = runTest {
            val (h, petSystem, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val player = h.players.get(sid)!!
            petSystem.summon(sid, "fire_familiar", player.roomId, player.level)
            h.drain()

            h.router.handle(sid, Command.PetDismiss)

            val events = h.drain()
            val infos = events.filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("dismiss", ignoreCase = true) }, "Expected dismiss message. got=$infos")
            assertTrue(petSystem.getActivePet(sid) == null, "Pet should be removed after dismiss")
        }

        @Test
        fun `pet dismiss with no pet shows error`() = runTest {
            val (h, _, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.PetDismiss)

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("no active pet", ignoreCase = true) }, "got=$errors")
        }

        @Test
        fun `pet name renames the active pet`() = runTest {
            val (h, petSystem, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            val player = h.players.get(sid)!!
            petSystem.summon(sid, "fire_familiar", player.roomId, player.level)
            h.drain()

            h.router.handle(sid, Command.PetName("Sparky"))

            val infos = h.drain().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(infos.any { it.contains("rename") && it.contains("Sparky") }, "Expected rename message. got=$infos")
            assertEquals("Sparky", petSystem.getActivePet(sid)?.name)
        }

        @Test
        fun `pet name with no pet shows error`() = runTest {
            val (h, _, _) = harness()
            val sid = SessionId(1)
            h.loginPlayer(sid, "Alice")
            h.drain()

            h.router.handle(sid, Command.PetName("Sparky"))

            val errors = h.drain().filterIsInstance<OutboundEvent.SendError>().map { it.text }
            assertTrue(errors.any { it.contains("no active pet", ignoreCase = true) }, "got=$errors")
        }
    }
}
