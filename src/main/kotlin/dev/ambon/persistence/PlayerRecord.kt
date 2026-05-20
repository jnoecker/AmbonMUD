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
    /** Zone ID (e.g. "academy") of the player's last death. Spirit-gate return target. */
    val lastDeathZone: String? = null,
    /**
     * Per-zone "last inn walked through" checkpoint, used by `depart` to send the player
     * back to a closer landmark than the zone start. Keys are zone IDs, values are full
     * `<zone>:<room>` RoomId strings.
     */
    val lastInnByZone: Map<String, String> = emptyMap(),
    val craftingSkills: Map<String, CraftingSkillState> = emptyMap(),
    val discoveredRecipes: Set<String> = emptySet(),
    val craftingSpecialization: String? = null,
    val factionStandings: Map<String, Int> = emptyMap(),
    /** Secondary currency balances (e.g. quest_points, honor, crafting_tokens). */
    val currencies: Map<String, Long> = emptyMap(),
    val friendsList: Set<String> = emptySet(),
    val bankGold: Long = 0L,
    val bankItems: List<ItemInstance> = emptyList(),
    val inventoryItems: List<ItemInstance> = emptyList(),
    val equippedItems: Map<String, ItemInstance> = emptyMap(),
    val activeSprite: String? = null,
    /** SHA-256 hash of the remember-me auth token. Empty string = no token issued. */
    val authTokenHash: String = "",
    /** Epoch millis when the auth token was issued. 0 = no token. */
    val authTokenIssuedAt: Long = 0L,
    /** Cumulative count of mobs killed by this player. */
    val mobsKilledTotal: Long = 0L,
    /** Number of dungeon instances completed by this player. */
    val dungeonsCompleted: Int = 0,
    /** Ability IDs explicitly learned via class trainers. */
    val learnedAbilityIds: Set<String> = emptySet(),
    /** Class names this player has unlocked (original class + any multi-class unlocks). */
    val unlockedClasses: Set<String> = emptySet(),
    /** Current prestige rank (0 = not yet prestiged). */
    val prestigeLevel: Int = 0,
    /** Cumulative XP spent on prestige ranks. */
    val prestigeXpSpent: Long = 0L,
    /** Cumulative PvP kills. */
    val pvpKills: Int = 0,
    /** Cumulative PvP deaths. */
    val pvpDeaths: Int = 0,
    /** JSON blob tracking daily/weekly quest progress, completions, and streak. */
    val dailyQuestData: String = "{}",
    /** Whether screen-reader accessibility mode is enabled. */
    val screenReaderEnabled: Boolean = false,
    /** Player-written custom description visible when others look at them. */
    val description: String = "",
    /** When true, items dropped by mobs the player kills are auto-looted into inventory. */
    val autolootEnabled: Boolean = false,
    /** Auto-flee HP% threshold (1..95); 0 disables wimpy. Default 10. */
    val wimpyThresholdPct: Int = 10,
    /** Epoch-ms of the last stat/ability respec. 0 means never respec'd. */
    val lastRespecAtMs: Long = 0L,
    /** Persisted dialogue-flag set used to gate quests behind prior conversations. */
    val dialogueFlags: Set<String> = emptySet(),
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

        // Normalize stat keys to uppercase (canonical format) and ensure all values are at least 1.
        // Legacy saves may have lowercase keys ("str") or zero values from old migration bugs.
        val mergedStats = DEFAULT_STATS + record.stats.mapKeys { (k, _) -> k.trim().uppercase() }
        val coercedStats = mergedStats.mapValues { (_, v) -> v.coerceAtLeast(1) }
        if (coercedStats != record.stats) record = record.copy(stats = coercedStats)

        // Normalize factionStandings keys to lowercase
        val normalizedFactions = record.factionStandings.normalizeKeys()
        if (normalizedFactions != record.factionStandings) record = record.copy(factionStandings = normalizedFactions)

        // Normalize craftingSkills keys to lowercase
        val normalizedCrafting = record.craftingSkills.normalizeKeys()
        if (normalizedCrafting != record.craftingSkills) record = record.copy(craftingSkills = normalizedCrafting)

        // Normalize equippedItems keys to lowercase
        val normalizedEquipped = record.equippedItems.normalizeKeys()
        if (normalizedEquipped != record.equippedItems) record = record.copy(equippedItems = normalizedEquipped)

        // Normalize currencies keys to lowercase
        val normalizedCurrencies = record.currencies.normalizeKeys()
        if (normalizedCurrencies != record.currencies) record = record.copy(currencies = normalizedCurrencies)

        return record
    }
}

private fun <V> Map<String, V>.normalizeKeys(): Map<String, V> =
    mapKeys { (k, _) -> k.trim().lowercase() }
