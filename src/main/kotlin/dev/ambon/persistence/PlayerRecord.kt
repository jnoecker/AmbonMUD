package dev.ambon.persistence

import dev.ambon.domain.achievement.AchievementState
import dev.ambon.domain.ids.RoomId
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
    val recallRoomId: RoomId? = null,
)
