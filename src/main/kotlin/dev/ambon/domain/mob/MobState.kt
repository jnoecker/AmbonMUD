package dev.ambon.domain.mob

import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.MobDrop
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.dialogue.DialogueTree

data class MobState(
    override val id: MobId,
    override var name: String,
    var roomId: RoomId,
    override val description: String = "",
    var hp: Int = 10,
    override var maxHp: Int = 10,
    override val damage: DamageRange = DamageRange(1, 4),
    override val armor: Int = 0,
    override val xpReward: Long = 30L,
    override val drops: List<MobDrop> = emptyList(),
    override val goldMin: Long = 0L,
    override val goldMax: Long = 0L,
    override val dialogue: DialogueTree? = null,
    override val behaviorTree: BtNode? = null,
    val templateKey: String = "",
    val spawnRoomId: RoomId? = null,
    val spawnDistanceMap: Map<RoomId, Int> = emptyMap(),
    override val questIds: List<String> = emptyList(),
    override val image: String? = null,
    override val video: String? = null,
    val category: String = "humanoid",
    val aggressive: Boolean = false,
    /** Non-null if this mob is a summoned pet. */
    val ownerSessionId: SessionId? = null,
    /** Character name of the pet's owner — sent to clients so they can identify their own pets. */
    val ownerName: String? = null,
    override val spells: List<MobSpell> = emptyList(),
    override val defaultAttack: String? = null,
    /** Threat multiplier for pet combat. 0.0 = no threat generation (default). */
    val threatMultiplier: Double = 0.0,
    override val level: Int = 1,
    override val role: MobRole = MobRole.COMBAT,
    /** Server-generated rare variant id (e.g. "shadow"), or null for an ordinary mob. */
    val variantId: String? = null,
    /** Variant display label (e.g. "Shadow-touched") for UI; null when not a variant. */
    val variantName: String? = null,
    /** CSS hex tint the client multiplies onto the sprite; null/empty = none. */
    val tint: String? = null,
    /** Client particle/overlay hint: swirl|embers|sparkle|frost|mist; null/empty = none. */
    val overlay: String? = null,
) : MobTemplate {
    val isPet: Boolean get() = ownerSessionId != null

    /** True when this mob is a server-generated rare variant. */
    val isVariant: Boolean get() = variantId != null
}
