package dev.ambon.domain.mob

import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.world.MobDrop
import dev.ambon.engine.behavior.BtNode
import dev.ambon.engine.dialogue.DialogueTree

/** Fields shared between the config-time [dev.ambon.domain.world.MobSpawn] and the live [MobState]. */
interface MobTemplate {
    val id: MobId
    val name: String
    val roomId: RoomId
    val description: String
    val maxHp: Int
    val damage: DamageRange
    val armor: Int
    val xpReward: Long
    val drops: List<MobDrop>
    val goldMin: Long
    val goldMax: Long
    val dialogue: DialogueTree?
    val behaviorTree: BtNode?
    val questIds: List<String>
    val image: String?
    val video: String?
}
