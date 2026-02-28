package dev.ambon.engine.dialogue

/** Result of [DialogueSystem.selectChoice]. */
sealed interface DialogueOutcome {
    /** The choice was processed; [action] is non-null if the choice carries a side-effect. */
    data class Ok(
        val action: String? = null,
    ) : DialogueOutcome

    /** The choice could not be processed; [message] describes why. */
    data class Err(
        val message: String,
    ) : DialogueOutcome
}
