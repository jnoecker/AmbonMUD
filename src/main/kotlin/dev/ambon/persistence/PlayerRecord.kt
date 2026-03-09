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

/** Default base stat value for new characters. */
private const val DEFAULT_STAT = 10

/** The canonical set of stat keys used by the game. */
val DEFAULT_STATS: Map<String, Int> = mapOf(
    "STR" to DEFAULT_STAT,
    "DEX" to DEFAULT_STAT,
    "CON" to DEFAULT_STAT,
    "INT" to DEFAULT_STAT,
    "WIS" to DEFAULT_STAT,
    "CHA" to DEFAULT_STAT,
)

data class PlayerRecord(
    val id: PlayerId,
    val name: String,
    val roomId: RoomId,
    val stats: Map<String, Int> = DEFAULT_STATS,
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
     * Old saves may have individual stat fields (strength, dexterity, etc.) instead of the
     * consolidated stats map. Jackson's FAIL_ON_UNKNOWN_PROPERTIES=false ignores those;
     * the defaults in [DEFAULT_STATS] apply. Legacy saves with constitution=0 are handled
     * by ensuring all stat values are at least 1.
     */
    fun migrateDefaults(): PlayerRecord {
        var record = this
        // Fix any zero-valued stats from legacy saves
        val fixedStats = record.stats.mapValues { (_, v) -> if (v == 0) DEFAULT_STAT else v }
        if (fixedStats != record.stats) record = record.copy(stats = fixedStats)
        val normalized = record.equippedItems.mapKeys { (k, _) -> k.trim().lowercase() }
        if (normalized != record.equippedItems) record = record.copy(equippedItems = normalized)
        return record
    }
}
