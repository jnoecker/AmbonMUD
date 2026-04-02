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
    override var roomId: RoomId,
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
    val aggressive: Boolean = false,
    /** Non-null if this mob is a summoned pet. */
    val ownerSessionId: SessionId? = null,
) : MobTemplate {
    val isPet: Boolean get() = ownerSessionId != null
}
