package dev.ambon.engine

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.puzzle.PuzzleDef
import dev.ambon.domain.puzzle.PuzzleReward
import dev.ambon.domain.puzzle.PuzzleType
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.World
import java.time.Clock

/**
 * Tracks per-player puzzle state and evaluates puzzle attempts.
 *
 * All state is session-scoped — it resets when the player logs out.
 * Called exclusively from the single-threaded engine dispatcher.
 */
class PuzzleSystem(
    private val world: World,
    private val clock: Clock,
) {
    /** Puzzles indexed by room for fast lookup when a player types `answer`. */
    private var puzzlesByRoom: Map<RoomId, List<PuzzleDef>> = buildRoomIndex(world)

    /** Riddle puzzles indexed by qualified mob ID. */
    private var riddlesByMob: Map<String, PuzzleDef> = buildMobIndex(world)

    /** Per-session set of solved puzzle IDs. */
    private val solvedPuzzles = mutableMapOf<SessionId, MutableSet<String>>()

    /** Per-session cooldown timestamps: puzzleId -> epoch-ms when available again. */
    private val cooldowns = mutableMapOf<SessionId, MutableMap<String, Long>>()

    /** Per-session sequence progress: puzzleId -> next step index. */
    private val sequenceProgress = mutableMapOf<SessionId, MutableMap<String, Int>>()

    /** Exits dynamically unlocked by puzzles: roomId -> set of unlocked directions. */
    private val unlockedExits = mutableMapOf<RoomId, MutableMap<Direction, RoomId>>()

    // ---- Public API ----

    /** Rebuild indexes after a world hot-reload. */
    fun rebuild(world: World) {
        puzzlesByRoom = buildRoomIndex(world)
        riddlesByMob = buildMobIndex(world)
    }

    /** Clean up all state for a disconnected session. */
    fun removeSession(sessionId: SessionId) {
        solvedPuzzles.remove(sessionId)
        cooldowns.remove(sessionId)
        sequenceProgress.remove(sessionId)
    }

    /** Returns riddle puzzles in the given room. */
    fun riddlesInRoom(roomId: RoomId): List<PuzzleDef> =
        puzzlesByRoom[roomId]?.filter { it.type == PuzzleType.RIDDLE } ?: emptyList()

    /** Returns sequence puzzles in the given room. */
    fun sequencePuzzlesInRoom(roomId: RoomId): List<PuzzleDef> =
        puzzlesByRoom[roomId]?.filter { it.type == PuzzleType.SEQUENCE } ?: emptyList()

    /** Returns all puzzles (riddle + sequence) in the given room. */
    fun puzzlesInRoom(roomId: RoomId): List<PuzzleDef> =
        puzzlesByRoom[roomId] ?: emptyList()

    /** Whether [sessionId] has currently solved [puzzleId] (not yet cleared by cooldown). */
    fun isSolved(sessionId: SessionId, puzzleId: String): Boolean =
        solvedPuzzles[sessionId]?.contains(puzzleId) == true

    /** Current sequence progress for [puzzleId] (0-based next step index). */
    fun sequenceStep(sessionId: SessionId, puzzleId: String): Int =
        sequenceProgress[sessionId]?.get(puzzleId) ?: 0

    /** Returns the riddle puzzle associated with a mob, if any. */
    fun riddleForMob(qualifiedMobId: String): PuzzleDef? = riddlesByMob[qualifiedMobId]

    /** Whether the given exit was dynamically unlocked by a puzzle. */
    fun isExitUnlocked(roomId: RoomId, direction: Direction): Boolean =
        unlockedExits[roomId]?.containsKey(direction) == true

    /** Returns the target room for a puzzle-unlocked exit. */
    fun getUnlockedExitTarget(roomId: RoomId, direction: Direction): RoomId? =
        unlockedExits[roomId]?.get(direction)

    /** Returns all dynamically unlocked exits for a room. */
    fun unlockedExitsForRoom(roomId: RoomId): Map<Direction, RoomId> =
        unlockedExits[roomId] ?: emptyMap()

    /**
     * Attempt to answer a riddle puzzle.
     *
     * Returns a [PuzzleResult] indicating success or failure.
     */
    fun attemptRiddleAnswer(
        sessionId: SessionId,
        puzzle: PuzzleDef,
        answer: String,
    ): PuzzleResult {
        if (puzzle.type != PuzzleType.RIDDLE) return PuzzleResult.NotApplicable

        // Check if already solved (and cooldown / one-time restrictions)
        val blocked = checkSolvedOrCooldown(sessionId, puzzle)
        if (blocked != null) return blocked

        val normalized = answer.trim().lowercase()
        return if (normalized in puzzle.acceptableAnswers) {
            markSolved(sessionId, puzzle)
            PuzzleResult.Success(puzzle.successMessage, puzzle.reward)
        } else {
            PuzzleResult.Failure(puzzle.failMessage)
        }
    }

    /**
     * Advance a sequence puzzle by one step.
     *
     * Called when a player interacts with a room feature (e.g. pulls a lever).
     * Returns null if no sequence puzzle matches the interaction, or a [PuzzleResult].
     */
    fun advanceSequence(
        sessionId: SessionId,
        roomId: RoomId,
        featureKeyword: String,
        action: String,
    ): PuzzleResult? {
        val puzzles = sequencePuzzlesInRoom(roomId)
        if (puzzles.isEmpty()) return null

        val normalizedKeyword = featureKeyword.trim().lowercase()
        val normalizedAction = action.trim().lowercase()

        for (puzzle in puzzles) {
            // Check if already solved
            val blocked = checkSolvedOrCooldown(sessionId, puzzle)
            if (blocked != null) continue // skip solved puzzles, try next

            val progress = sequenceProgress
                .getOrPut(sessionId) { mutableMapOf() }
            val currentStep = progress[puzzle.id] ?: 0

            if (currentStep >= puzzle.steps.size) continue

            val expectedStep = puzzle.steps[currentStep]
            if (expectedStep.featureKeyword == normalizedKeyword &&
                expectedStep.action == normalizedAction
            ) {
                val nextStep = currentStep + 1
                if (nextStep >= puzzle.steps.size) {
                    // Puzzle complete
                    progress.remove(puzzle.id)
                    markSolved(sessionId, puzzle)
                    return PuzzleResult.Success(puzzle.successMessage, puzzle.reward)
                } else {
                    progress[puzzle.id] = nextStep
                    return PuzzleResult.SequenceAdvanced(nextStep, puzzle.steps.size)
                }
            } else if (expectedStep.action == normalizedAction && currentStep > 0) {
                // Wrong feature for an in-progress sequence with same action type
                if (puzzle.resetOnFail) {
                    progress.remove(puzzle.id)
                    return PuzzleResult.Failure(puzzle.failMessage)
                }
            }
        }
        return null
    }

    // ---- Internal helpers ----

    private fun checkSolvedOrCooldown(sessionId: SessionId, puzzle: PuzzleDef): PuzzleResult? {
        val solved = solvedPuzzles[sessionId]?.contains(puzzle.id) == true
        if (!solved) return null

        if (puzzle.cooldownMs <= 0L) {
            // One-time puzzle, already solved
            return PuzzleResult.AlreadySolved
        }

        val cooldownMap = cooldowns[sessionId] ?: return null
        val availableAt = cooldownMap[puzzle.id] ?: return null
        val now = clock.millis()
        return if (now < availableAt) {
            PuzzleResult.OnCooldown((availableAt - now) / 1000)
        } else {
            // Cooldown expired, allow retry
            solvedPuzzles[sessionId]?.remove(puzzle.id)
            cooldownMap.remove(puzzle.id)
            null
        }
    }

    private fun markSolved(sessionId: SessionId, puzzle: PuzzleDef) {
        solvedPuzzles.getOrPut(sessionId) { mutableSetOf() }.add(puzzle.id)
        if (puzzle.cooldownMs > 0L) {
            cooldowns.getOrPut(sessionId) { mutableMapOf() }[puzzle.id] =
                clock.millis() + puzzle.cooldownMs
        }
        // Apply unlock_exit reward immediately to world state
        val reward = puzzle.reward
        if (reward is PuzzleReward.UnlockExit) {
            unlockedExits.getOrPut(puzzle.roomId) { mutableMapOf() }[reward.direction] = reward.targetRoom
        }
    }

    companion object {
        private fun buildRoomIndex(world: World): Map<RoomId, List<PuzzleDef>> =
            world.puzzleDefinitions.groupBy { it.roomId }

        private fun buildMobIndex(world: World): Map<String, PuzzleDef> =
            world.puzzleDefinitions
                .filter { it.type == PuzzleType.RIDDLE && it.mobId != null }
                .associateBy { it.mobId!! }
    }
}

/** Result of a puzzle attempt. */
sealed interface PuzzleResult {
    data class Success(
        val message: String,
        val reward: PuzzleReward,
    ) : PuzzleResult

    data class Failure(
        val message: String,
    ) : PuzzleResult

    data class SequenceAdvanced(
        val currentStep: Int,
        val totalSteps: Int,
    ) : PuzzleResult

    /** The puzzle was already solved and is not repeatable. */
    data object AlreadySolved : PuzzleResult

    /** The puzzle is on cooldown. */
    data class OnCooldown(
        val secondsRemaining: Long,
    ) : PuzzleResult

    /** The puzzle type doesn't match the attempt (e.g. answering a sequence puzzle). */
    data object NotApplicable : PuzzleResult
}
