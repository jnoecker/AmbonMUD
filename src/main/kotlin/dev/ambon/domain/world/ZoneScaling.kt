package dev.ambon.domain.world

/**
 * Controls how a zone resolves mob and quest levels at runtime.
 *
 * - [STATIC] — mobs and quests use their authored `level` fields as-is. Current
 *   behaviour; the default for zones that don't declare scaling.
 * - [BOUNDED] — mobs and quests scale to the level of the highest-level player
 *   in the zone, clamped to the zone's `levelRange`. Players below the floor
 *   get floor-level content; players above the ceiling get ceiling-level
 *   content. Keeps progression gates intact while letting mixed-level parties
 *   play the same zone.
 * - [PLAYER] — mobs and quests scale directly to the reference player's level
 *   with no bounds. Intended for tutorial zones, endgame social hubs, or any
 *   content that should always match the player.
 */
enum class ScalingMode {
    STATIC,
    BOUNDED,
    PLAYER,
    ;

    companion object {
        /**
         * Parses a mode string from world YAML. Null/blank returns [STATIC]
         * so zones without explicit scaling keep their authored levels.
         */
        fun parse(raw: String?): ScalingMode {
            if (raw.isNullOrBlank()) return STATIC
            val normalized = raw.trim().uppercase()
            return entries.firstOrNull { it.name == normalized }
                ?: throw IllegalArgumentException(
                    "Unknown scaling mode '$raw' (expected one of ${entries.joinToString { it.name.lowercase() }})",
                )
        }
    }
}

/**
 * Per-zone scaling configuration. Immutable; parsed once at world load and
 * consulted by spawn/completion paths at runtime.
 */
data class ZoneScaling(
    val mode: ScalingMode = ScalingMode.STATIC,
    /** Inclusive min/max bounds for [ScalingMode.BOUNDED]. Required when mode is BOUNDED; ignored otherwise. */
    val levelRange: IntRange? = null,
) {
    /**
     * Computes the effective level to use for content in this zone given a
     * reference player level (typically the highest-level player in the zone
     * when a mob respawns or a quest turns in) and the authored level on the
     * content itself.
     *
     * - [ScalingMode.STATIC]: returns [authoredLevel] when set, else [referencePlayerLevel].
     * - [ScalingMode.BOUNDED]: clamps [referencePlayerLevel] to [levelRange];
     *   falls back to [authoredLevel] or `range.first` when no reference is given.
     * - [ScalingMode.PLAYER]: returns [referencePlayerLevel]; falls back to
     *   [authoredLevel] or 1.
     *
     * The returned level is always at least 1.
     */
    fun resolveLevel(referencePlayerLevel: Int?, authoredLevel: Int?): Int {
        val resolved = when (mode) {
            ScalingMode.STATIC -> authoredLevel ?: referencePlayerLevel ?: 1
            ScalingMode.BOUNDED -> {
                val range = levelRange
                if (range == null) {
                    authoredLevel ?: referencePlayerLevel ?: 1
                } else if (referencePlayerLevel != null) {
                    referencePlayerLevel.coerceIn(range)
                } else {
                    authoredLevel?.coerceIn(range) ?: range.first
                }
            }
            ScalingMode.PLAYER -> referencePlayerLevel ?: authoredLevel ?: 1
        }
        return resolved.coerceAtLeast(1)
    }
}
