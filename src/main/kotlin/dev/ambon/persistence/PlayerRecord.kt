package dev.ambon.persistence

import dev.ambon.domain.achievement.AchievementState
import dev.ambon.domain.crafting.CraftingSkillState
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.mail.MailMessage
import dev.ambon.domain.quest.QuestState

@JvmInline
value class PlayerId(
    val value: Long,
)

data class PlayerRecord(
    val id: PlayerId,
    val name: String,
    val roomId: RoomId,
    val strength: Int = 10,
    val dexterity: Int = 10,
    val constitution: Int = 10,
    val intelligence: Int = 10,
    val wisdom: Int = 10,
    val charisma: Int = 10,
    val gender: String = "enby",
    val race: String = "HUMAN",
    val playerClass: String = "WARRIOR",
    val level: Int = 1,
    val xpTotal: Long = 0L,
    val createdAtEpochMs: Long,
    val lastSeenEpochMs: Long,
    val passwordHash: String = "",
    val ansiEnabled: Boolean = false,
    val isStaff: Boolean = false,
    val hp: Int = 0,
    val mana: Int = 20,
    val maxMana: Int = 20,
    val gold: Long = 0L,
    val activeQuests: Map<String, QuestState> = emptyMap(),
    val completedQuestIds: Set<String> = emptySet(),
    val unlockedAchievementIds: Set<String> = emptySet(),
    val achievementProgress: Map<String, AchievementState> = emptyMap(),
    val activeTitle: String? = null,
    val inbox: List<MailMessage> = emptyList(),
    val guildId: String? = null,
    val recallRoomId: RoomId? = null,
    val craftingSkills: Map<String, CraftingSkillState> = emptyMap(),
    val friendsList: Set<String> = emptySet(),
    val inventoryItems: List<ItemInstance> = emptyList(),
    val equippedItems: Map<String, ItemInstance> = emptyMap(),
) {
    /**
     * Applies legacy migration fixes after deserialization.
     * Old saves stored constitution=0; remap to the base stat value (10).
     */
    fun migrateDefaults(): PlayerRecord {
        var record = this
        if (record.constitution == 0) record = record.copy(constitution = 10)
        val normalized = record.equippedItems.mapKeys { (k, _) -> k.trim().lowercase() }
        if (normalized != record.equippedItems) record = record.copy(equippedItems = normalized)
        return record
    }
}
