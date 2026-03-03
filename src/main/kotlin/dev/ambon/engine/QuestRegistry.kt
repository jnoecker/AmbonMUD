package dev.ambon.engine

import dev.ambon.domain.quest.QuestDef

class QuestRegistry : DefinitionRegistry<String, QuestDef>({ it.id }) {
    fun questsForMob(mobId: String): List<QuestDef> = all().filter { it.giverMobId == mobId }
}
