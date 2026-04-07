package dev.ambon.domain.world

import dev.ambon.domain.ids.RoomId

/**
 * A trainer NPC that can teach abilities for one or more classes.
 *
 * A trainer with multiple entries in [classNames] is a "multi-class trainer" — a single NPC
 * in a single room who can teach abilities from all the listed classes (e.g. a combat
 * instructor who teaches WARRIOR + ROGUE + RANGER). Players still unlock each class
 * individually via `train unlock <class>` and each class counts separately in
 * [dev.ambon.engine.PlayerState.unlockedClasses].
 */
data class TrainerDefinition(
    val id: String,
    val name: String,
    /** Class IDs (uppercase) this trainer teaches. Always at least one entry. */
    val classNames: List<String>,
    val roomId: RoomId,
    val image: String? = null,
) {
    init {
        require(classNames.isNotEmpty()) { "TrainerDefinition '$id' must teach at least one class" }
    }

    /** The first class this trainer teaches, used for single-class legacy display contexts. */
    val primaryClass: String get() = classNames.first()

    /** True when this trainer teaches more than one class. */
    val isMultiClass: Boolean get() = classNames.size > 1

    /** Case-insensitive membership check. */
    fun teachesClass(className: String): Boolean =
        classNames.any { it.equals(className, ignoreCase = true) }
}
