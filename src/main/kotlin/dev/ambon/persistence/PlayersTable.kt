package dev.ambon.persistence

import com.fasterxml.jackson.core.type.TypeReference
import dev.ambon.domain.achievement.AchievementState
import dev.ambon.domain.crafting.CraftingSkillState
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.mail.MailMessage
import dev.ambon.domain.quest.QuestState
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.statements.UpdateBuilder

private val activeQuestsType = object : TypeReference<Map<String, QuestState>>() {}
private val completedQuestIdsType = object : TypeReference<Set<String>>() {}
private val unlockedAchievementIdsType = object : TypeReference<Set<String>>() {}
private val achievementProgressType = object : TypeReference<Map<String, AchievementState>>() {}
private val mailInboxType = object : TypeReference<List<MailMessage>>() {}
private val craftingSkillsType = object : TypeReference<Map<String, CraftingSkillState>>() {}
private val friendsListType = object : TypeReference<Set<String>>() {}
private val inventoryItemsType = object : TypeReference<List<ItemInstance>>() {}
private val equippedItemsType = object : TypeReference<Map<String, ItemInstance>>() {}

/** Deserialises JSON with a fallback to [default] on any parse failure. */
private fun <T> safeReadJson(json: String, type: TypeReference<T>, default: T): T =
    runCatching { jsonMapper.readValue(json, type) }.getOrDefault(default)

object PlayersTable : Table("players") {
    val id = long("id").autoIncrement("player_id_seq")
    val name = varchar("name", 16)
    val nameLower = varchar("name_lower", 16)
    val roomId = varchar("room_id", 128)
    val strength = integer("strength").default(10)
    val dexterity = integer("dexterity").default(10)
    val constitution = integer("constitution").default(10)
    val intelligence = integer("intelligence").default(10)
    val wisdom = integer("wisdom").default(10)
    val charisma = integer("charisma").default(10)
    val gender = varchar("gender", 16).default("enby")
    val race = varchar("race", 32).default("HUMAN")
    val playerClass = varchar("player_class", 32).default("WARRIOR")
    val level = integer("level").default(1)
    val xpTotal = long("xp_total").default(0L)
    val createdAtEpochMs = long("created_at_epoch_ms")
    val lastSeenEpochMs = long("last_seen_epoch_ms")
    val passwordHash = varchar("password_hash", 72).default("")
    val ansiEnabled = bool("ansi_enabled").default(false)
    val isStaff = bool("is_staff").default(false)
    val hp = integer("hp").default(0)
    val mana = integer("mana").default(20)
    val maxMana = integer("max_mana").default(20)
    val gold = long("gold").default(0L)
    val activeQuests = text("active_quests").default("{}")
    val completedQuestIds = text("completed_quest_ids").default("[]")
    val unlockedAchievementIds = text("unlocked_achievement_ids").default("[]")
    val achievementProgress = text("achievement_progress").default("{}")
    val activeTitle = varchar("active_title", 64).nullable()
    val mailInbox = text("mail_inbox").default("[]")
    val guildId = varchar("guild_id", 64).nullable()
    val recallRoomId = varchar("recall_room_id", 128).nullable()
    val craftingSkills = text("crafting_skills").default("{}")
    val friendsList = text("friends_list").default("[]")
    val inventoryItems = text("inventory_items").default("[]")
    val equippedItems = text("equipped_items").default("{}")

    override val primaryKey = PrimaryKey(id)

    /** Reads all columns from a [ResultRow] into a [PlayerRecord]. */
    fun readRecord(row: ResultRow): PlayerRecord =
        PlayerRecord(
            id = PlayerId(row[id]),
            name = row[name],
            roomId = RoomId(row[roomId]),
            strength = row[strength],
            dexterity = row[dexterity],
            constitution = row[constitution],
            intelligence = row[intelligence],
            wisdom = row[wisdom],
            charisma = row[charisma],
            gender = row[gender],
            race = row[race],
            playerClass = row[playerClass],
            level = row[level],
            xpTotal = row[xpTotal],
            createdAtEpochMs = row[createdAtEpochMs],
            lastSeenEpochMs = row[lastSeenEpochMs],
            passwordHash = row[passwordHash],
            ansiEnabled = row[ansiEnabled],
            isStaff = row[isStaff],
            hp = row[hp],
            mana = row[mana],
            maxMana = row[maxMana],
            gold = row[gold],
            activeQuests = safeReadJson(row[activeQuests], activeQuestsType, emptyMap()),
            completedQuestIds = safeReadJson(row[completedQuestIds], completedQuestIdsType, emptySet()),
            unlockedAchievementIds = safeReadJson(row[unlockedAchievementIds], unlockedAchievementIdsType, emptySet()),
            achievementProgress = safeReadJson(row[achievementProgress], achievementProgressType, emptyMap()),
            activeTitle = row[activeTitle],
            inbox = safeReadJson(row[mailInbox], mailInboxType, emptyList()),
            guildId = row[guildId],
            recallRoomId = row[recallRoomId]?.let { RoomId(it) },
            craftingSkills = safeReadJson(row[craftingSkills], craftingSkillsType, emptyMap()),
            friendsList = safeReadJson(row[friendsList], friendsListType, emptySet()),
            inventoryItems = safeReadJson(row[inventoryItems], inventoryItemsType, emptyList()),
            equippedItems = safeReadJson(row[equippedItems], equippedItemsType, emptyMap()),
        ).migrateDefaults()

    /** Writes all [PlayerRecord] fields into an insert or upsert [statement]. */
    fun writeRecord(statement: UpdateBuilder<*>, record: PlayerRecord) {
        statement[id] = record.id.value
        statement[name] = record.name
        statement[nameLower] = record.name.lowercase()
        statement[roomId] = record.roomId.value
        statement[strength] = record.strength
        statement[dexterity] = record.dexterity
        statement[constitution] = record.constitution
        statement[intelligence] = record.intelligence
        statement[wisdom] = record.wisdom
        statement[charisma] = record.charisma
        statement[gender] = record.gender
        statement[race] = record.race
        statement[playerClass] = record.playerClass
        statement[level] = record.level
        statement[xpTotal] = record.xpTotal
        statement[createdAtEpochMs] = record.createdAtEpochMs
        statement[lastSeenEpochMs] = record.lastSeenEpochMs
        statement[passwordHash] = record.passwordHash
        statement[ansiEnabled] = record.ansiEnabled
        statement[isStaff] = record.isStaff
        statement[hp] = record.hp
        statement[mana] = record.mana
        statement[maxMana] = record.maxMana
        statement[gold] = record.gold
        statement[activeQuests] = jsonMapper.writeValueAsString(record.activeQuests)
        statement[completedQuestIds] = jsonMapper.writeValueAsString(record.completedQuestIds)
        statement[unlockedAchievementIds] = jsonMapper.writeValueAsString(record.unlockedAchievementIds)
        statement[achievementProgress] = jsonMapper.writeValueAsString(record.achievementProgress)
        statement[activeTitle] = record.activeTitle
        statement[mailInbox] = jsonMapper.writeValueAsString(record.inbox)
        statement[guildId] = record.guildId
        statement[recallRoomId] = record.recallRoomId?.value
        statement[craftingSkills] = jsonMapper.writeValueAsString(record.craftingSkills)
        statement[friendsList] = jsonMapper.writeValueAsString(record.friendsList)
        statement[inventoryItems] = jsonMapper.writeValueAsString(record.inventoryItems)
        statement[equippedItems] = jsonMapper.writeValueAsString(record.equippedItems)
    }
}
