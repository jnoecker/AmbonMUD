package dev.ambon.domain.puzzle

import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.Direction

/**
 * Fully-resolved puzzle definition loaded from zone YAML.
 *
 * IDs are zone-qualified (e.g. `sunken_temple:sphinx_riddle`).
 */
data class PuzzleDef(
    val id: String,
    val type: PuzzleType,
    /** Qualified mob ID of the NPC that poses the riddle (riddle type only). */
    val mobId: String? = null,
    val roomId: RoomId,
    /** Riddle question text. */
    val question: String? = null,
    /** All accepted answers (lowercase-normalized). Includes the canonical answer. */
    val acceptableAnswers: List<String> = emptyList(),
    /** Ordered interaction steps (sequence type only). */
    val steps: List<PuzzleStep> = emptyList(),
    val reward: PuzzleReward,
    val failMessage: String,
    val successMessage: String,
    /** 0 = one-time per session; >0 = repeatable after cooldown (ms). */
    val cooldownMs: Long = 0L,
    /** Whether a failed sequence resets to the beginning. */
    val resetOnFail: Boolean = true,
)

enum class PuzzleType {
    RIDDLE,
    SEQUENCE,
}

data class PuzzleStep(
    val featureKeyword: String,
    val action: String,
)

sealed interface PuzzleReward {
    data class UnlockExit(
        val direction: Direction,
        val targetRoom: RoomId,
    ) : PuzzleReward

    data class GiveItem(
        val itemId: String,
    ) : PuzzleReward

    data class GiveGold(
        val amount: Long,
    ) : PuzzleReward

    data class GiveXp(
        val amount: Long,
    ) : PuzzleReward
}
