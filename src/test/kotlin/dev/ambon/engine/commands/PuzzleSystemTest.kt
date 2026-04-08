package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.puzzle.PuzzleType
import dev.ambon.domain.world.Direction
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PuzzleSystem
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.drainAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PuzzleSystemTest {
    private val world = dev.ambon.test.TestWorlds.okPuzzles
    private val startRoomId = world.startRoom // ok_puzzles:entrance

    private data class Harness(
        val sid: SessionId,
        val players: dev.ambon.engine.PlayerRegistry,
        val items: ItemRegistry,
        val router: CommandRouter,
        val outbound: LocalOutboundBus,
        val worldState: WorldStateRegistry,
        val puzzleSystem: PuzzleSystem,
        val clock: MutableClock,
    )

    private suspend fun harness(): Harness {
        val clock = MutableClock(0L)
        val items = ItemRegistry()
        items.loadSpawns(world.itemSpawns)
        val outbound = LocalOutboundBus()
        val players = dev.ambon.test.buildTestPlayerRegistry(startRoomId, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val worldState = WorldStateRegistry(world)
        val puzzleSystem = PuzzleSystem(world = world, clock = clock)
        val router = buildTestRouter(
            world = world,
            players = players,
            mobs = mobs,
            items = items,
            combat = CombatSystem(players, mobs, items, outbound),
            outbound = outbound,
            worldState = worldState,
            puzzleSystem = puzzleSystem,
            clock = clock,
        )
        val sid = SessionId(1)
        val res = players.login(sid, "Tester", "password")
        require(res == LoginResult.Ok)
        outbound.drainAll()
        return Harness(sid, players, items, router, outbound, worldState, puzzleSystem, clock)
    }

    // ---- WorldLoader tests ----

    @Test
    fun `world loads puzzle definitions`() {
        val puzzles = world.puzzleDefinitions
        assertTrue(puzzles.isNotEmpty(), "Expected at least one puzzle definition")

        val riddle = puzzles.first { it.id == "ok_puzzles:sphinx_riddle" }
        assertEquals(PuzzleType.RIDDLE, riddle.type)
        assertEquals(RoomId("ok_puzzles:entrance"), riddle.roomId)
        assertEquals("ok_puzzles:sphinx", riddle.mobId)
        assertTrue(riddle.acceptableAnswers.contains("mountain"))
        assertTrue(riddle.acceptableAnswers.contains("a mountain"))

        val sequence = puzzles.first { it.id == "ok_puzzles:lever_sequence" }
        assertEquals(PuzzleType.SEQUENCE, sequence.type)
        assertEquals(3, sequence.steps.size)
        assertEquals("red", sequence.steps[0].featureKeyword)
        assertEquals("pull", sequence.steps[0].action)
    }

    // ---- Riddle tests ----

    @Test
    fun `answer correct riddle grants unlock_exit reward`() = runTest {
        val h = harness()

        // Before answering, north exit should not exist
        h.router.handle(h.sid, Command.Move(Direction.NORTH))
        val moveFail = h.outbound.drainAll()
        assertTrue(
            moveFail.any { it is OutboundEvent.SendError && it.text.contains("can't go that way") },
            "Expected blocked before puzzle solved, got: $moveFail",
        )

        // Answer correctly
        h.router.handle(h.sid, Command.Answer("mountain"))
        val outs = h.outbound.drainAll()
        assertTrue(
            outs.any { it is OutboundEvent.SendInfo && it.text.contains("sphinx nods") },
            "Expected success message, got: $outs",
        )
        assertTrue(
            outs.any { it is OutboundEvent.SendInfo && it.text.contains("passage to the north") },
            "Expected exit unlock message, got: $outs",
        )

        // Now north exit should work
        h.router.handle(h.sid, Command.Move(Direction.NORTH))
        val moveSuccess = h.outbound.drainAll()
        assertEquals(RoomId("ok_puzzles:hidden_room"), h.players.get(h.sid)?.roomId)
    }

    @Test
    fun `answer correct riddle re-sends room state for client refresh`() = runTest {
        val h = harness()

        // Answer correctly — solving should re-look the room so clients refresh
        // minimap/exits/puzzle state immediately instead of waiting for the next move.
        h.router.handle(h.sid, Command.Answer("mountain"))
        val outs = h.outbound.drainAll()

        // sendLook emits the room title as a SendText, so its presence confirms
        // ctx.sendLook() ran after grantReward().
        assertTrue(
            outs.any { it is OutboundEvent.SendText && it.text == "Puzzle Chamber Entrance" },
            "Expected room title to be re-sent via sendLook after puzzle solve, got: $outs",
        )
    }

    @Test
    fun `answer wrong riddle shows fail message`() = runTest {
        val h = harness()
        h.router.handle(h.sid, Command.Answer("wrong answer"))
        val outs = h.outbound.drainAll()
        assertTrue(
            outs.any { it is OutboundEvent.SendError && it.text.contains("Think harder") },
            "Expected fail message, got: $outs",
        )
    }

    @Test
    fun `answer accepts alternative answers`() = runTest {
        val h = harness()
        h.router.handle(h.sid, Command.Answer("a mountain"))
        val outs = h.outbound.drainAll()
        assertTrue(
            outs.any { it is OutboundEvent.SendInfo && it.text.contains("sphinx nods") },
            "Expected success with alternate answer, got: $outs",
        )
    }

    @Test
    fun `answer is case insensitive`() = runTest {
        val h = harness()
        h.router.handle(h.sid, Command.Answer("MOUNTAIN"))
        val outs = h.outbound.drainAll()
        assertTrue(
            outs.any { it is OutboundEvent.SendInfo && it.text.contains("sphinx nods") },
            "Expected success with uppercase answer, got: $outs",
        )
    }

    @Test
    fun `already solved puzzle shows already solved message`() = runTest {
        val h = harness()

        // Solve it first
        h.router.handle(h.sid, Command.Answer("mountain"))
        h.outbound.drainAll()

        // Try again
        h.router.handle(h.sid, Command.Answer("mountain"))
        val outs = h.outbound.drainAll()
        assertTrue(
            outs.any { it is OutboundEvent.SendInfo && it.text.contains("already solved") },
            "Expected already-solved message, got: $outs",
        )
    }

    @Test
    fun `answer with no puzzle in room shows error`() = runTest {
        val h = harness()

        // Move to lever_room which has riddles defined
        h.players.moveTo(h.sid, RoomId("ok_puzzles:hidden_room"))
        h.outbound.drainAll()

        h.router.handle(h.sid, Command.Answer("anything"))
        val outs = h.outbound.drainAll()
        assertTrue(
            outs.any { it is OutboundEvent.SendError && it.text.contains("no puzzle to answer") },
            "Expected no-puzzle error, got: $outs",
        )
    }

    // ---- Sequence puzzle tests ----

    @Test
    fun `correct lever sequence grants reward`() = runTest {
        val h = harness()
        val me = h.players.get(h.sid)!!
        val goldBefore = me.gold

        // Pull levers in correct order: red, blue, green
        h.router.handle(h.sid, Command.Pull("red"))
        h.outbound.drainAll()
        h.router.handle(h.sid, Command.Pull("blue"))
        h.outbound.drainAll()
        h.router.handle(h.sid, Command.Pull("green"))
        val outs = h.outbound.drainAll()

        assertTrue(
            outs.any { it is OutboundEvent.SendInfo && it.text.contains("shower of gold") },
            "Expected sequence success message, got: $outs",
        )

        val goldAfter = h.players.get(h.sid)!!.gold
        assertEquals(goldBefore + 50, goldAfter, "Expected 50 gold reward")
    }

    @Test
    fun `wrong lever sequence resets on fail`() = runTest {
        val h = harness()

        // Start correct: red
        h.router.handle(h.sid, Command.Pull("red"))
        h.outbound.drainAll()

        // Wrong: green instead of blue
        h.router.handle(h.sid, Command.Pull("green"))
        val outs = h.outbound.drainAll()
        assertTrue(
            outs.any { it is OutboundEvent.SendError && it.text.contains("mechanism resets") },
            "Expected reset message, got: $outs",
        )

        // Now we need to start over — pull red, blue, green
        h.router.handle(h.sid, Command.Pull("red"))
        h.outbound.drainAll()
        h.router.handle(h.sid, Command.Pull("blue"))
        h.outbound.drainAll()
        h.router.handle(h.sid, Command.Pull("green"))
        val finalOuts = h.outbound.drainAll()
        assertTrue(
            finalOuts.any { it is OutboundEvent.SendInfo && it.text.contains("shower of gold") },
            "Expected success after retry, got: $finalOuts",
        )
    }

    // ---- XP reward test ----

    @Test
    fun `riddle with give_xp reward grants experience`() = runTest {
        val h = harness()

        // Move to lever_room where xp_riddle is
        h.players.moveTo(h.sid, RoomId("ok_puzzles:lever_room"))
        h.outbound.drainAll()

        val xpBefore = h.players.get(h.sid)!!.xpTotal

        h.router.handle(h.sid, Command.Answer("echo"))
        val outs = h.outbound.drainAll()

        assertTrue(
            outs.any { it is OutboundEvent.SendInfo && it.text.contains("wisdom flows") },
            "Expected XP riddle success, got: $outs",
        )
        assertTrue(
            outs.any { it is OutboundEvent.SendText && it.text.contains("100 XP") },
            "Expected XP grant message, got: $outs",
        )
    }

    // ---- Item reward test ----

    @Test
    fun `riddle with give_item reward adds item to inventory`() = runTest {
        val h = harness()

        // Move to lever_room where item_riddle is
        h.players.moveTo(h.sid, RoomId("ok_puzzles:lever_room"))
        h.outbound.drainAll()

        val invBefore = h.items.inventory(h.sid)
        assertTrue(invBefore.isEmpty(), "Inventory should start empty")

        // Answer the "footsteps" riddle
        h.router.handle(h.sid, Command.Answer("footsteps"))
        val outs = h.outbound.drainAll()

        assertTrue(
            outs.any { it is OutboundEvent.SendInfo && it.text.contains("materializes") },
            "Expected item riddle success, got: $outs",
        )

        val invAfter = h.items.inventory(h.sid)
        assertEquals(1, invAfter.size, "Should have 1 item in inventory")
        assertEquals("gem", invAfter[0].item.keyword)
    }

    // ---- Parser tests ----

    @Test
    fun `parser parses answer command`() {
        assertEquals(Command.Answer("mountain"), CommandParser.parse("answer mountain"))
        assertEquals(Command.Answer("a mountain"), CommandParser.parse("answer a mountain"))
    }

    @Test
    fun `parser returns Invalid for empty answer`() {
        assertTrue(CommandParser.parse("answer") is Command.Invalid)
        assertTrue(CommandParser.parse("answer   ") is Command.Invalid)
    }

    // ---- Exit display test ----

    @Test
    fun `exits command shows puzzle-unlocked exits`() = runTest {
        val h = harness()

        // Before solving: only east exit visible
        h.router.handle(h.sid, Command.Exits)
        val beforeOuts = h.outbound.drainAll()
        val exitsBefore = beforeOuts.filterIsInstance<OutboundEvent.SendInfo>()
            .first { it.text.startsWith("Exits:") }.text
        assertFalse(exitsBefore.contains("north"), "North should not be visible before puzzle solved")

        // Solve the riddle
        h.router.handle(h.sid, Command.Answer("mountain"))
        h.outbound.drainAll()

        // After solving: north should appear
        h.router.handle(h.sid, Command.Exits)
        val afterOuts = h.outbound.drainAll()
        val exitsAfter = afterOuts.filterIsInstance<OutboundEvent.SendInfo>()
            .first { it.text.startsWith("Exits:") }.text
        assertTrue(exitsAfter.contains("north"), "North should be visible after puzzle solved, got: $exitsAfter")
    }

    // ---- PuzzleSystem unit tests ----

    @Test
    fun `puzzleSystem tracks riddles by room`() {
        val clock = MutableClock(0L)
        val system = PuzzleSystem(world, clock)
        val riddles = system.riddlesInRoom(RoomId("ok_puzzles:entrance"))
        assertEquals(1, riddles.size)
        assertEquals("ok_puzzles:sphinx_riddle", riddles[0].id)
    }

    @Test
    fun `puzzleSystem tracks riddles by mob`() {
        val clock = MutableClock(0L)
        val system = PuzzleSystem(world, clock)
        val riddle = system.riddleForMob("ok_puzzles:sphinx")
        assertNotNull(riddle)
        assertEquals("ok_puzzles:sphinx_riddle", riddle!!.id)
    }

    @Test
    fun `puzzleSystem returns null for unknown mob`() {
        val clock = MutableClock(0L)
        val system = PuzzleSystem(world, clock)
        assertNull(system.riddleForMob("ok_puzzles:nonexistent"))
    }

    @Test
    fun `removeSession clears all state for session`() {
        val clock = MutableClock(0L)
        val system = PuzzleSystem(world, clock)
        val sid = SessionId(42)
        val riddle = system.riddlesInRoom(RoomId("ok_puzzles:entrance")).first()

        // Solve the riddle
        system.attemptRiddleAnswer(sid, riddle, "mountain")
        assertTrue(system.isExitUnlocked(RoomId("ok_puzzles:entrance"), Direction.NORTH))

        // Remove session clears solved state (but unlocked exits persist as world state)
        system.removeSession(sid)
    }
}
