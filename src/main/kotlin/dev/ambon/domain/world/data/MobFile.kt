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
    /**
     * Whether the server may spawn rare cosmetic variants of this mob. Defaults
     * to true for combat mobs; set false to opt a mob out of auto-generated
     * variants (e.g. unique named bosses or strictly-themed creatures).
     */
    val rareVariants: Boolean = true,
    /**
     * Optional spawn condition gating when this mob appears: time of day,
     * weather, season, world-event flags, and/or a random `chance`. When set to
     * a non-trivial condition, the mob's entire spawn lifecycle is owned by the
     * conditional spawn handler (it fades out when the condition ends).
     */
    val condition: SpawnConditionFile? = null,
)
