package dev.ambon.domain.world.data

data class MobFile(
    val name: String,
    val description: String = "",
    /**
     * Legacy single-room shorthand. Equivalent to `spawns: [{ room: <value> }]`.
     * Prefer [spawns] for new content; this field is preserved so old YAML keeps loading.
     */
    val room: String? = null,
    /**
     * Spawn placements for this mob template. Each entry creates [MobSpawnFile.count]
     * runtime instances in the named room. When empty, [room] is used as a fallback
     * (one instance in that room).
     */
    val spawns: List<MobSpawnFile> = emptyList(),
    /**
     * What this mob is for: combat, vendor, quest_giver, dialog, or prop.
     * Non-combat roles refuse attack commands and skip combat-stat computation.
     * Null/missing defaults to combat to preserve legacy behaviour.
     */
    val role: String? = null,
    val tier: String? = null,
    val level: Int? = null,
    /**
     * Multiplicative tuning knobs applied to tier×level baselines. Builders use these to express
     * "noticeably tougher than baseline" (e.g. `hpMult: 1.25`) without having to type absolute
     * numbers. Multipliers are applied to tier-derived values only; explicit overrides
     * ([hp], [minDamage], etc.) still win. Default 1.0 (no change).
     */
    val hpMult: Double? = null,
    val dmgMult: Double? = null,
    val xpMult: Double? = null,
    val goldMult: Double? = null,
    val hp: Int? = null,
    val minDamage: Int? = null,
    val maxDamage: Int? = null,
    val armor: Int? = null,
    val xpReward: Long? = null,
    val drops: List<MobDropFile> = emptyList(),
    val respawnSeconds: Long? = null,
    val goldMin: Long? = null,
    val goldMax: Long? = null,
    val dialogue: Map<String, DialogueNodeFile> = emptyMap(),
    val behavior: BehaviorFile? = null,
    val quests: List<String> = emptyList(),
    val faction: String? = null,
    val image: String? = null,
    val video: String? = null,
    /** Visual category for default sprite selection (e.g. humanoid, beast, undead). */
    val category: String? = null,
    /** Spells this mob can cast during combat. Key = spell id. */
    val spells: Map<String, MobSpellFile> = emptyMap(),
    /** If set, this spell replaces the mob's default melee attack. Must reference a key in [spells]. */
    val defaultAttack: String? = null,
)
