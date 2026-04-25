package dev.ambon.persistence

import com.fasterxml.jackson.core.type.TypeReference
import dev.ambon.domain.achievement.AchievementState
import dev.ambon.domain.crafting.CraftingSkillState
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.mail.MailMessage
import dev.ambon.domain.quest.QuestState
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder

private val statsType = object : TypeReference<Map<String, Int>>() {}
private val activeQuestsType = object : TypeReference<Map<String, QuestState>>() {}
private val completedQuestIdsType = object : TypeReference<Set<String>>() {}
private val dialogueFlagsType = object : TypeReference<Set<String>>() {}
private val unlockedAchievementIdsType = object : TypeReference<Set<String>>() {}
private val achievementProgressType = object : TypeReference<Map<String, AchievementState>>() {}
private val mailInboxType = object : TypeReference<List<MailMessage>>() {}
private val craftingSkillsType = object : TypeReference<Map<String, CraftingSkillState>>() {}
private val discoveredRecipesType = object : TypeReference<Set<String>>() {}
private val factionStandingsType = object : TypeReference<Map<String, Int>>() {}
private val currenciesType = object : TypeReference<Map<String, Long>>() {}
private val friendsListType = object : TypeReference<Set<String>>() {}
private val inventoryItemsType = object : TypeReference<List<ItemInstance>>() {}
private val equippedItemsType = object : TypeReference<Map<String, ItemInstance>>() {}
private val learnedAbilityIdsType = object : TypeReference<Set<String>>() {}
private val unlockedClassesType = object : TypeReference<Set<String>>() {}

object PlayersTable : Table("players") {
    val id = long("id").autoIncrement("player_id_seq")
    val name = varchar("name", 16)
    val nameLower = varchar("name_lower", 16)
    val roomId = varchar("room_id", 128)
    val statsJson = text("stats_json").default("{}")
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
    val lastDeathZone = varchar("last_death_zone", 64).nullable()
    val craftingSkills = text("crafting_skills").default("{}")
    val discoveredRecipes = text("discovered_recipes").default("[]")
    val craftingSpecialization = varchar("crafting_specialization", 64).nullable()
    val factionStandings = text("faction_standings").default("{}")
    val currencies = text("currencies").default("{}")
    val friendsList = text("friends_list").default("[]")
    val bankGold = long("bank_gold").default(0L)
    val bankItems = text("bank_items").default("[]")
    val inventoryItems = text("inventory_items").default("[]")
    val equippedItems = text("equipped_items").default("{}")
    val activeSprite = varchar("active_sprite", 100).nullable()
    val authTokenHash = varchar("auth_token_hash", 64).default("")
    val authTokenIssuedAt = long("auth_token_issued_at").default(0L)
    val mobsKilledTotal = long("mobs_killed_total").default(0L)
    val dungeonsCompleted = integer("dungeons_completed").default(0)
    val learnedAbilityIds = text("learned_ability_ids").default("[]")
    val unlockedClasses = text("unlocked_classes").default("[]")
    val prestigeLevel = integer("prestige_level").default(0)
    val prestigeXpSpent = long("prestige_xp_spent").default(0L)
    val pvpKills = integer("pvp_kills").default(0)
    val pvpDeaths = integer("pvp_deaths").default(0)
    val dailyQuestData = text("daily_quest_data").default("{}")
    val screenReaderEnabled = bool("screen_reader_enabled").default(false)
    val description = text("description").default("")
    val autolootEnabled = bool("autoloot_enabled").default(false)
    val lastRespecAtMs = long("last_respec_at_ms").default(0L)
    val dialogueFlags = text("dialogue_flags").default("[]")

    override val primaryKey = PrimaryKey(id)

    /** Reads all columns from a [ResultRow] into a [PlayerRecord]. */
    fun readRecord(row: ResultRow): PlayerRecord =
        PlayerRecord(
            id = PlayerId(row[id]),
            name = row[name],
            roomId = RoomId(row[roomId]),
            stats = safeReadJson(row[statsJson], statsType, DEFAULT_STATS),
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
            lastDeathZone = row[lastDeathZone],
            craftingSkills = safeReadJson(row[craftingSkills], craftingSkillsType, emptyMap()),
            discoveredRecipes = safeReadJson(row[discoveredRecipes], discoveredRecipesType, emptySet()),
            craftingSpecialization = row[craftingSpecialization],
            factionStandings = safeReadJson(row[factionStandings], factionStandingsType, emptyMap()),
            currencies = safeReadJson(row[currencies], currenciesType, emptyMap()),
            friendsList = safeReadJson(row[friendsList], friendsListType, emptySet()),
            bankGold = row[bankGold],
            bankItems = safeReadJson(row[bankItems], inventoryItemsType, emptyList()),
            inventoryItems = safeReadJson(row[inventoryItems], inventoryItemsType, emptyList()),
            equippedItems = safeReadJson(row[equippedItems], equippedItemsType, emptyMap()),
            activeSprite = row[activeSprite],
            authTokenHash = row[authTokenHash],
            authTokenIssuedAt = row[authTokenIssuedAt],
            mobsKilledTotal = row[mobsKilledTotal],
            dungeonsCompleted = row[dungeonsCompleted],
            learnedAbilityIds = safeReadJson(row[learnedAbilityIds], learnedAbilityIdsType, emptySet()),
            unlockedClasses = safeReadJson(row[unlockedClasses], unlockedClassesType, emptySet()),
            prestigeLevel = row[prestigeLevel],
            prestigeXpSpent = row[prestigeXpSpent],
            pvpKills = row[pvpKills],
            pvpDeaths = row[pvpDeaths],
            dailyQuestData = row[dailyQuestData],
            screenReaderEnabled = row[screenReaderEnabled],
            description = row[description],
            autolootEnabled = row[autolootEnabled],
            lastRespecAtMs = row[lastRespecAtMs],
            dialogueFlags = safeReadJson(row[dialogueFlags], dialogueFlagsType, emptySet()),
        ).migrateDefaults()

    /** Writes all [PlayerRecord] fields into an insert or upsert [statement]. */
    fun writeRecord(statement: UpdateBuilder<*>, record: PlayerRecord) {
        statement[id] = record.id.value
        statement[name] = record.name
        statement[nameLower] = record.name.lowercase()
        statement[roomId] = record.roomId.value
        statement[statsJson] = jsonMapper.writeValueAsString(record.stats)
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
        statement[lastDeathZone] = record.lastDeathZone
        statement[craftingSkills] = jsonMapper.writeValueAsString(record.craftingSkills)
        statement[discoveredRecipes] = jsonMapper.writeValueAsString(record.discoveredRecipes)
        statement[craftingSpecialization] = record.craftingSpecialization
        statement[factionStandings] = jsonMapper.writeValueAsString(record.factionStandings)
        statement[currencies] = jsonMapper.writeValueAsString(record.currencies)
        statement[friendsList] = jsonMapper.writeValueAsString(record.friendsList)
        statement[bankGold] = record.bankGold
        statement[bankItems] = jsonMapper.writeValueAsString(record.bankItems)
        statement[inventoryItems] = jsonMapper.writeValueAsString(record.inventoryItems)
        statement[equippedItems] = jsonMapper.writeValueAsString(record.equippedItems)
        statement[activeSprite] = record.activeSprite
        statement[authTokenHash] = record.authTokenHash
        statement[authTokenIssuedAt] = record.authTokenIssuedAt
        statement[mobsKilledTotal] = record.mobsKilledTotal
        statement[dungeonsCompleted] = record.dungeonsCompleted
        statement[learnedAbilityIds] = jsonMapper.writeValueAsString(record.learnedAbilityIds)
        statement[unlockedClasses] = jsonMapper.writeValueAsString(record.unlockedClasses)
        statement[prestigeLevel] = record.prestigeLevel
        statement[prestigeXpSpent] = record.prestigeXpSpent
        statement[pvpKills] = record.pvpKills
        statement[pvpDeaths] = record.pvpDeaths
        statement[dailyQuestData] = record.dailyQuestData
        statement[screenReaderEnabled] = record.screenReaderEnabled
        statement[description] = record.description
        statement[autolootEnabled] = record.autolootEnabled
        statement[lastRespecAtMs] = record.lastRespecAtMs
        statement[dialogueFlags] = jsonMapper.writeValueAsString(record.dialogueFlags)
    }
}
