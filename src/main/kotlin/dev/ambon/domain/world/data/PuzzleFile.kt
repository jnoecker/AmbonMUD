package dev.ambon.domain.world.data

/**
 * YAML representation of a puzzle definition within a zone file.
 *
 * Supports two puzzle types:
 * - **riddle** — an NPC poses a question; the player answers with `answer <text>`.
 * - **sequence** — the player must interact with room features in the correct order.
 */
data class PuzzleFile(
    val type: String = "",
    /** Mob ID of the NPC that poses the riddle (riddle type only). */
    val mobId: String? = null,
    /** Room where the puzzle is located. */
    val roomId: String = "",
    /** The riddle question text (riddle type only). */
    val question: String? = null,
    /** The canonical correct answer (riddle type only). */
    val answer: String? = null,
    /** Additional accepted answer strings (riddle type only). */
    val acceptableAnswers: List<String> = emptyList(),
    /** Ordered interaction steps (sequence type only). */
    val steps: List<PuzzleStepFile> = emptyList(),
    /** Reward granted on successful completion. */
    val reward: PuzzleRewardFile = PuzzleRewardFile(),
    /** Message shown on failure. */
    val failMessage: String = "That doesn't seem right.",
    /** Message shown on success. */
    val successMessage: String = "Success!",
    /** 0 = one-time per player session; >0 = repeatable after cooldown (ms). */
    val cooldownMs: Long = 0L,
    /** Whether a failed sequence resets to the beginning (sequence type only). */
    val resetOnFail: Boolean = true,
)

/** A single step in a sequence puzzle. */
data class PuzzleStepFile(
    /** Feature keyword to interact with. */
    val feature: String = "",
    /** Action verb (e.g. "pull", "push"). */
    val action: String = "",
)

/** Reward granted when a puzzle is solved. */
data class PuzzleRewardFile(
    /** Reward type: unlock_exit, give_item, give_gold, give_xp. */
    val type: String = "",
    /** Direction of the exit to unlock (unlock_exit only). */
    val exitDirection: String? = null,
    /** Target room for the unlocked exit (unlock_exit only). */
    val targetRoom: String? = null,
    /** Item ID to give the player (give_item only). */
    val itemId: String? = null,
    /** Gold amount (give_gold only). */
    val gold: Long = 0L,
    /** XP amount (give_xp only). */
    val xp: Long = 0L,
)
