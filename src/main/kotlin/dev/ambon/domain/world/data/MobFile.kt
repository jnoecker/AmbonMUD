package dev.ambon.domain.world.data

data class MobFile(
    val name: String,
    val description: String = "",
    val room: String,
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
)
