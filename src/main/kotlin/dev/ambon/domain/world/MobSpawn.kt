package dev.ambon.domain.world

import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.mob.MobTemplate
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.dialogue.DialogueTree

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
) : MobTemplate
