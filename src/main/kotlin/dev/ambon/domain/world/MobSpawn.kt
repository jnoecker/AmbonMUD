package dev.ambon.domain.world

import dev.ambon.config.MobTierConfig
import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.mob.MobRole
import dev.ambon.domain.mob.MobSpell
import dev.ambon.domain.mob.MobTemplate
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.dialogue.DialogueTree

/**
 * Authored stat overrides that won at load time. Preserved on [MobSpawn] so
 * that spawn-time rescaling (for zones with non-STATIC scaling) can replay
 * the tier × level math while still honouring the author's explicit values.
 */
data class MobStatOverrides(
    val hp: Int? = null,
    val minDamage: Int? = null,
    val maxDamage: Int? = null,
    val armor: Int? = null,
    val xpReward: Long? = null,
    val goldMin: Long? = null,
    val goldMax: Long? = null,
)

data class MobSpawn(
    override val id: MobId,
    override val name: String,
    override val roomId: RoomId,
    override val description: String = "",
    override val maxHp: Int = 10,
    override val damage: DamageRange = DamageRange(1, 4),
    override val armor: Int = 0,
    override val xpReward: Long = 30L,
    override val drops: List<MobDrop> = emptyList(),
    val respawnSeconds: Long? = null,
    override val goldMin: Long = 0L,
    override val goldMax: Long = 0L,
    override val dialogue: DialogueTree? = null,
    override val behaviorTree: BtNode? = null,
    override val questIds: List<String> = emptyList(),
    val faction: String? = null,
    override val image: String? = null,
    override val video: String? = null,
    val aggressive: Boolean = false,
    val category: String = "humanoid",
    override val spells: List<MobSpell> = emptyList(),
    override val defaultAttack: String? = null,
    override val level: Int = 1,
    override val role: MobRole = MobRole.COMBAT,
    /**
     * The tier config used to resolve stats at load time. Preserved so
     * spawn-time rescaling can replay the formulas at a different level
     * without re-reading config. Null means "don't rescale this mob even in
     * scaling zones" — typically because no tier was available at load.
     */
    val tier: MobTierConfig? = null,
    /** Which stats the author explicitly set. Overrides stay fixed even when scaling shifts level. */
    val overrides: MobStatOverrides = MobStatOverrides(),
) : MobTemplate
