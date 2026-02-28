package dev.ambon.persistence

import dev.ambon.domain.achievement.AchievementState
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.mail.MailMessage
import dev.ambon.domain.quest.QuestState

/**
 * Flat-field DTO for YAML and Redis (JSON) serialization.
 * Uses plain types (Long, String) so Jackson requires no special handling for the
 * [PlayerId] / [RoomId] value classes used in [PlayerRecord].
 */
internal data class PlayerDto(
    val id: Long,
    val name: String,
    val roomId: String,
    val strength: Int = 10,
    val dexterity: Int = 10,
    val constitution: Int = 10,
    val intelligence: Int = 10,
    val wisdom: Int = 10,
    val charisma: Int = 10,
    val race: String = "HUMAN",
    val playerClass: String = "WARRIOR",
    val level: Int = 1,
    val xpTotal: Long = 0L,
    val createdAtEpochMs: Long,
    val lastSeenEpochMs: Long,
    val passwordHash: String = "",
    val ansiEnabled: Boolean = false,
    val isStaff: Boolean = false,
    val mana: Int = 20,
    val maxMana: Int = 20,
    val gold: Long = 0L,
    val activeQuests: Map<String, QuestState> = emptyMap(),
    val completedQuestIds: Set<String> = emptySet(),
    val unlockedAchievementIds: Set<String> = emptySet(),
    val achievementProgress: Map<String, AchievementState> = emptyMap(),
    val activeTitle: String? = null,
    val inbox: List<MailMessage> = emptyList(),
) {
    fun toDomain(): PlayerRecord {
        // Legacy migration: old files/cache stored constitution=0; remap to base 10
        val migratedCon = if (constitution == 0) 10 else constitution
        return PlayerRecord(
            id = PlayerId(id),
            name = name,
            roomId = RoomId(roomId),
            strength = strength,
            dexterity = dexterity,
            constitution = migratedCon,
            intelligence = intelligence,
            wisdom = wisdom,
            charisma = charisma,
            race = race,
            playerClass = playerClass,
            level = level,
            xpTotal = xpTotal,
            createdAtEpochMs = createdAtEpochMs,
            lastSeenEpochMs = lastSeenEpochMs,
            passwordHash = passwordHash,
            ansiEnabled = ansiEnabled,
            isStaff = isStaff,
            mana = mana,
            maxMana = maxMana,
            gold = gold,
            activeQuests = activeQuests,
            completedQuestIds = completedQuestIds,
            unlockedAchievementIds = unlockedAchievementIds,
            achievementProgress = achievementProgress,
            activeTitle = activeTitle,
            inbox = inbox,
        )
    }

    companion object {
        fun from(record: PlayerRecord): PlayerDto =
            PlayerDto(
                id = record.id.value,
                name = record.name,
                roomId = record.roomId.value,
                strength = record.strength,
                dexterity = record.dexterity,
                constitution = record.constitution,
                intelligence = record.intelligence,
                wisdom = record.wisdom,
                charisma = record.charisma,
                race = record.race,
                playerClass = record.playerClass,
                level = record.level,
                xpTotal = record.xpTotal,
                createdAtEpochMs = record.createdAtEpochMs,
                lastSeenEpochMs = record.lastSeenEpochMs,
                passwordHash = record.passwordHash,
                ansiEnabled = record.ansiEnabled,
                isStaff = record.isStaff,
                mana = record.mana,
                maxMana = record.maxMana,
                gold = record.gold,
                activeQuests = record.activeQuests,
                completedQuestIds = record.completedQuestIds,
                unlockedAchievementIds = record.unlockedAchievementIds,
                achievementProgress = record.achievementProgress,
                activeTitle = record.activeTitle,
                inbox = record.inbox,
            )
    }
}
