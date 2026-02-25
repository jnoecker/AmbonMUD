package dev.ambon.engine

import dev.ambon.domain.quest.QuestDef

class QuestRegistry {
    private val quests = mutableMapOf<String, QuestDef>()

    fun register(quest: QuestDef) {
        quests[quest.id] = quest
    }

    fun get(questId: String): QuestDef? = quests[questId]

    fun questsForMob(mobId: String): List<QuestDef> = quests.values.filter { it.giverMobId == mobId }

    fun all(): Collection<QuestDef> = quests.values
}
