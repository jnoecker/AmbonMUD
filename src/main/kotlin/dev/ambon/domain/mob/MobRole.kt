package dev.ambon.domain.mob

/**
 * Classifies what a mob is *for*, independent of how tough it is. Gates
 * which behaviours a mob exposes — combatants can be attacked and award XP,
 * vendors/quest-givers/dialog mobs surface their social affordances but
 * refuse combat, and props are examine-only set dressing.
 */
enum class MobRole {
    /** Can be attacked, fights back via behaviour tree, awards XP and drops. */
    COMBAT,

    /** Shopkeeper. Has a shop; cannot be attacked. */
    VENDOR,

    /** Offers and accepts quests. Cannot be attacked. */
    QUEST_GIVER,

    /** Conversational NPC — dialog trees only, no combat. */
    DIALOG,

    /** Examine-only flavour entity (statues, totems, etc.). No interaction beyond look. */
    PROP,
    ;

    /** True when this role participates in the combat system. */
    val isCombatant: Boolean
        get() = this == COMBAT

    companion object {
        /**
         * Parses a role string from world YAML, case-insensitive. Returns [COMBAT]
         * when [raw] is null or blank so legacy mob definitions retain their
         * previous behaviour without explicit opt-in.
         */
        fun parse(raw: String?): MobRole {
            if (raw.isNullOrBlank()) return COMBAT
            val normalized = raw.trim().uppercase().replace('-', '_').replace(' ', '_')
            return entries.firstOrNull { it.name == normalized }
                ?: throw IllegalArgumentException(
                    "Unknown mob role '$raw' (expected one of ${entries.joinToString { it.name.lowercase() }})",
                )
        }
    }
}
