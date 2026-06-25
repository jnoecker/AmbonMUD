package dev.ambon.engine

import dev.ambon.domain.StatMap
import dev.ambon.domain.achievement.AchievementState
import dev.ambon.domain.arcanum.ArcanumJournal
import dev.ambon.domain.crafting.CraftingSkillState
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mail.MailMessage
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.quest.QuestState
import dev.ambon.domain.world.Direction
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.PlayerId
import dev.ambon.persistence.PlayerRecord
import dev.ambon.persistence.jsonMapper

data class PlayerState(
    val sessionId: SessionId,
    var name: String,
    var roomId: RoomId,
    var playerId: PlayerId? = null,
    var baseMaxHp: Int = BASE_MAX_HP,
    var hp: Int = BASE_MAX_HP,
    var maxHp: Int = BASE_MAX_HP,
    var stats: StatMap = StatMap.of(
        "STR" to BASE_STAT,
        "DEX" to BASE_STAT,
        "CON" to BASE_STAT,
        "INT" to BASE_STAT,
        "WIS" to BASE_STAT,
        "CHA" to BASE_STAT,
    ),
    var gender: String = "enby",
    var race: String = "HUMAN",
    var playerClass: String = "WARRIOR",
    var level: Int = 1,
    var xpTotal: Long = 0L,
    var ansiEnabled: Boolean = false,
    var isStaff: Boolean = false,
    var mana: Int = BASE_MANA,
    var maxMana: Int = BASE_MANA,
    var baseMana: Int = BASE_MANA,
    var gold: Long = 0L,
    // Immutable after creation — cached here so persistIfClaimed avoids a repo lookup.
    val createdAtEpochMs: Long = 0L,
    var passwordHash: String = "",
    var activeQuests: Map<String, QuestState> = emptyMap(),
    var completedQuestIds: Set<String> = emptySet(),
    /**
     * Persisted dialogue-flag set. Set entries are added by dialogue choice
     * actions (see `unlock_flag:<name>` in DialogueQuestHandler) and consumed
     * by [QuestSystem] to gate quests behind prior conversations.
     */
    var dialogueFlags: MutableSet<String> = mutableSetOf(),
    /** Zones whose intro cinematic this player has already watched (auto-plays once per zone). */
    var seenZoneCinematics: MutableSet<String> = mutableSetOf(),
    var unlockedAchievementIds: Set<String> = emptySet(),
    var achievementProgress: Map<String, AchievementState> = emptyMap(),
    var activeTitle: String? = null,
    var inbox: MutableList<MailMessage> = mutableListOf(),
    /** Non-null while the player is composing an outgoing mail message. */
    var mailCompose: MailComposeState? = null,
    var guildId: String? = null,
    var guildRank: String? = null,
    var guildTag: String? = null,
    var recallRoomId: RoomId? = null,
    /** Zone ID (e.g. "academy") of the player's last death. Drives `depart` from the sanctum. */
    var lastDeathZone: String? = null,
    /**
     * Per-zone checkpoint: the most recent inn room the player walked through in each zone.
     * Used by `depart` to send the player back to a closer landmark than the zone start.
     * Entries persist across deaths so a previously-discovered inn keeps acting as a checkpoint.
     */
    var lastInnByZone: MutableMap<String, RoomId> = mutableMapOf(),
    var friendsList: MutableSet<String> = mutableSetOf(),
    var bankGold: Long = 0L,
    var bankItems: MutableList<dev.ambon.domain.items.ItemInstance> = mutableListOf(),
    /** Epoch-ms timestamp after which recall is available again. Runtime-only; not persisted. */
    var recallCooldownUntilMs: Long = 0L,
    /** Epoch-ms of the last stat/ability respec. Persisted so the cooldown survives logout. */
    var lastRespecAtMs: Long = 0L,
    /**
     * Epoch-ms after which this player's race-specific passive ability can fire again. Persisted
     * (unlike runtime ability cooldowns) precisely because the racial passive cheats death — a relog
     * must not reset the cooldown mid-fight and let it be re-triggered. See [RacialAbilitySystem].
     */
    var racialAbilityCooldownUntilMs: Long = 0L,
    /**
     * Epoch-ms until which the player cannot be selected as a mob's attack target (Aetherae phase,
     * Lithae stone form). Runtime-only; not persisted.
     */
    var untargetableUntilMs: Long = 0L,
    /** Epoch-ms until which the player's outgoing damage is multiplied by [damageBoostMultiplier]
     *  (Ophirae wrath). Runtime-only; not persisted. */
    var damageBoostUntilMs: Long = 0L,
    /** Outgoing-damage multiplier applied while [damageBoostUntilMs] is in the future. */
    var damageBoostMultiplier: Double = 1.0,
    var craftingSkills: MutableMap<String, CraftingSkillState> = mutableMapOf(),
    var discoveredRecipes: MutableSet<String> = mutableSetOf(),
    /** Flight-master room ids (`zone:room`) this player has visited and can fast-travel to. */
    var discoveredFlightPoints: MutableSet<String> = mutableSetOf(),
    var craftingSpecialization: String? = null,
    var factionStandings: MutableMap<String, Int> = mutableMapOf(),
    /** Secondary currency balances (e.g. quest_points, honor, crafting_tokens). */
    var currencies: MutableMap<String, Long> = mutableMapOf(),
    /** Epoch-ms timestamp after which gathering is available again. Runtime-only; not persisted. */
    var gatherCooldownUntilMs: Long = 0L,
    /** Epoch-ms of last command input. Runtime-only; used for idle calculation. */
    var lastActivityEpochMs: Long = 0L,
    /** Chosen sprite variant imageId, or null for auto (highest unlocked tier). */
    var activeSprite: String? = null,
    /** Non-null while staff is possessing a mob. Runtime-only; not persisted. */
    var possessedMobId: MobId? = null,
    /** The player's real room before possession began. Runtime-only; not persisted. */
    var prePossessRoomId: RoomId? = null,
    /** When true, player is hidden from room player lists, who, and movement broadcasts. Runtime-only. */
    var invisible: Boolean = false,
    /** Cached flag indicating whether this player owns a house. Runtime-only; set on login. */
    var hasHouse: Boolean = false,
    /** Cumulative count of mobs killed by this player. */
    var mobsKilledTotal: Long = 0L,
    /** Number of dungeon instances completed by this player. */
    var dungeonsCompleted: Int = 0,
    /** Ability IDs explicitly learned via class trainers. */
    var learnedAbilityIds: MutableSet<String> = mutableSetOf(),
    /** Class names this player has unlocked (original class + any multi-class unlocks). */
    var unlockedClasses: MutableSet<String> = mutableSetOf(),
    /** Current prestige rank (0 = not yet prestiged). */
    var prestigeLevel: Int = 0,
    /** Cumulative XP spent on prestige ranks. */
    var prestigeXpSpent: Long = 0L,
    /** Cumulative PvP kills. */
    var pvpKills: Int = 0,
    /** Cumulative PvP deaths. */
    var pvpDeaths: Int = 0,
    /** Daily/weekly quest tracking state. */
    var dailyQuestState: DailyQuestState = DailyQuestState(),
    /** Whether screen-reader accessibility mode is enabled. */
    var screenReaderEnabled: Boolean = false,
    /**
     * When true, room music/ambient and NPC dialogue voice clip URLs are printed inline as plain
     * text (`[music] <url>` etc.) so players on non-web clients can play the audio themselves.
     */
    var audioLinksEnabled: Boolean = false,
    /** Player-written custom description visible when others look at them. */
    var description: String = "",
    /** When true, items dropped by mobs the player kills are auto-looted into inventory. */
    var autolootEnabled: Boolean = false,
    /** When true, room descriptions append a peek line naming what lies through each open exit. */
    var autopeekEnabled: Boolean = false,
    /**
     * Auto-flee HP-percent threshold (0..95, where 0 disables). When the player's HP is at or
     * below this percentage of max after a mob hit, combat is broken automatically. Default `10`
     * is intentionally cautious — it kicks in once the player is in real danger but late enough
     * not to interrupt routine fights.
     */
    var wimpyThresholdPct: Int = 10,
    /**
     * SHA-256 hash of the remember-me auth token.
     * Tracked on [PlayerState] so [persistIfClaimed] doesn't wipe it on every
     * save — otherwise disconnect would erase the token the client still
     * holds in localStorage and force a password prompt on next login.
     */
    var authTokenHash: String = "",
    /** Epoch-ms the current [authTokenHash] was issued. 0 when no token is set. */
    var authTokenIssuedAt: Long = 0L,
    /**
     * Direction the player most recently moved through to enter their current room.
     * Used by flee to prefer retreating back the way they came. Runtime-only; not persisted.
     */
    var lastEnterDirection: Direction? = null,
    /**
     * Last room music/ambient URL emitted inline to this session (see [audioLinksEnabled]).
     * Runtime-only dedup memory so the same track isn't reprinted on every room move — only on
     * change. Not persisted.
     */
    var lastEmittedMusicUrl: String? = null,
    var lastEmittedAmbientUrl: String? = null,
    /**
     * True while the player is under the Akathavae pledge: combat is forbidden and the
     * world is leveled through illumination (recording rooms, creatures, and items in
     * the Arcanum journal) instead of killing.
     */
    var isAkathavae: Boolean = false,
    /** Epoch-ms the current Akathavae pledge was taken. 0 = never pledged. */
    var akathavaePledgedAtMs: Long = 0L,
    /** Epoch-ms the pledge was last renounced — re-pledging is gated behind a cooldown. */
    var akathavaeRenouncedAtMs: Long = 0L,
    /** The class held before pledging as an Akathavae; restored on renounce. Null when not pledged. */
    var preAkathavaeClass: String? = null,
    /** The player's Arcanum journal. Persisted as a JSON blob (see [dev.ambon.persistence.PlayerRecord.arcanumData]). */
    var arcanum: ArcanumJournal = ArcanumJournal(),
) {
    data class MailComposeState(
        val recipientName: String,
        val lines: MutableList<String> = mutableListOf(),
        /** Gold to attach to the letter, consumed from the sender when sent. */
        val attachGold: Long = 0L,
        /** Inventory keyword of an item to attach, resolved/removed when sent. */
        val attachItemKeyword: String? = null,
    )

    companion object {
        const val BASE_MAX_HP = 10
        const val BASE_MANA = 20
        const val BASE_STAT = 10

        /** Returns the bonus conferred by [total] stat points above [BASE_STAT], divided by [divisor]. */
        fun statBonus(total: Int, divisor: Int): Int = (total - BASE_STAT) / divisor
    }

    override fun toString(): String =
        "PlayerState(sessionId=$sessionId, name=$name, roomId=$roomId, playerId=$playerId, " +
            "baseMaxHp=$baseMaxHp, hp=$hp, maxHp=$maxHp, stats=$stats, " +
            "race=$race, playerClass=$playerClass, level=$level, xpTotal=$xpTotal, " +
            "ansiEnabled=$ansiEnabled, isStaff=$isStaff, " +
            "mana=$mana, maxMana=$maxMana, baseMana=$baseMana, gold=$gold, " +
            "activeQuests=${activeQuests.keys}, completedQuestIds=$completedQuestIds, " +
            "unlockedAchievementIds=$unlockedAchievementIds, activeTitle=$activeTitle, " +
            "createdAtEpochMs=$createdAtEpochMs, passwordHash=<redacted>)"
}

/**
 * Tracks a player's daily and weekly quest progress, completions, and streak.
 * Serialized as a JSON blob into [dev.ambon.persistence.PlayerRecord.dailyQuestData].
 */
data class DailyQuestState(
    /** ISO date of last daily reset, e.g. "2026-04-03". */
    var lastDailyResetDate: String = "",
    /** ISO date of last weekly reset. */
    var lastWeeklyResetDate: String = "",
    /** Indices of today's daily quests the player has completed. */
    var dailyCompletions: MutableSet<Int> = mutableSetOf(),
    /** Indices of this week's weekly quests the player has completed. */
    var weeklyCompletions: MutableSet<Int> = mutableSetOf(),
    /** Progress count per daily quest index. */
    var dailyProgress: MutableMap<Int, Int> = mutableMapOf(),
    /** Progress count per weekly quest index. */
    var weeklyProgress: MutableMap<Int, Int> = mutableMapOf(),
    /** Number of consecutive days the player completed all dailies. */
    var streakDays: Int = 0,
    /** ISO date the streak was last extended. */
    var lastStreakDate: String = "",
)

/** Increases HP by [amount], clamped to [maxHp]. Returns `true` if HP actually changed. */
fun PlayerState.healHp(amount: Int): Boolean {
    val new = (hp + amount).coerceAtMost(maxHp)
    return if (new != hp) {
        hp = new
        true
    } else {
        false
    }
}

/** Decreases HP by [amount], clamped to 0. Staff members are immune to damage. */
fun PlayerState.takeDamage(amount: Int) {
    if (isStaff) return
    hp = (hp - amount).coerceAtLeast(0)
}

/** True while the player can't be picked as a mob's target (Aetherae phase / Lithae stone form). */
fun PlayerState.isUntargetable(nowMs: Long): Boolean = nowMs < untargetableUntilMs

/** Outgoing-damage multiplier from an active damage buff (Ophirae wrath), else 1.0. */
fun PlayerState.outgoingDamageMultiplier(nowMs: Long): Double =
    if (nowMs < damageBoostUntilMs) damageBoostMultiplier else 1.0

/** Increases mana by [amount], clamped to [maxMana]. Returns `true` if mana actually changed. */
fun PlayerState.healMana(amount: Int): Boolean {
    val new = (mana + amount).coerceAtMost(maxMana)
    return if (new != mana) {
        mana = new
        true
    } else {
        false
    }
}

/** Decreases mana by [amount], clamped to 0. Staff members have infinite mana. */
fun PlayerState.spendMana(amount: Int) {
    if (isStaff) return
    mana = (mana - amount).coerceAtLeast(0)
}

/** Creates a [PlayerState] from a persisted [PlayerRecord], binding it to [sessionId]. */
fun PlayerRecord.toPlayerState(sessionId: SessionId): PlayerState =
    PlayerState(
        sessionId = sessionId,
        name = name,
        roomId = roomId,
        playerId = id,
        hp = hp,
        stats = StatMap(stats),
        gender = gender.lowercase(),
        race = race,
        playerClass = playerClass,
        level = level,
        xpTotal = xpTotal,
        ansiEnabled = ansiEnabled,
        isStaff = isStaff,
        mana = mana,
        maxMana = maxMana,
        gold = gold,
        createdAtEpochMs = createdAtEpochMs,
        passwordHash = passwordHash,
        activeQuests = activeQuests,
        completedQuestIds = completedQuestIds,
        dialogueFlags = dialogueFlags.toMutableSet(),
        seenZoneCinematics = seenZoneCinematics.toMutableSet(),
        unlockedAchievementIds = unlockedAchievementIds,
        achievementProgress = achievementProgress,
        activeTitle = activeTitle,
        inbox = inbox.toMutableList(),
        guildId = guildId,
        recallRoomId = recallRoomId,
        lastDeathZone = lastDeathZone,
        lastInnByZone = lastInnByZone.mapValues { (_, v) -> RoomId(v) }.toMutableMap(),
        craftingSkills = craftingSkills.map { (key, state) ->
            key.lowercase() to state
        }.toMap().toMutableMap(),
        discoveredRecipes = discoveredRecipes.toMutableSet(),
        discoveredFlightPoints = discoveredFlightPoints.toMutableSet(),
        craftingSpecialization = craftingSpecialization,
        factionStandings = factionStandings.toMutableMap(),
        currencies = currencies.toMutableMap(),
        friendsList = friendsList.toMutableSet(),
        bankGold = bankGold,
        bankItems = bankItems.toMutableList(),
        activeSprite = activeSprite,
        mobsKilledTotal = mobsKilledTotal,
        dungeonsCompleted = dungeonsCompleted,
        learnedAbilityIds = learnedAbilityIds.toMutableSet(),
        // Auto-populate original class if unlockedClasses is empty (new/migrated characters).
        unlockedClasses = unlockedClasses.ifEmpty { setOf(playerClass) }.toMutableSet(),
        prestigeLevel = prestigeLevel,
        prestigeXpSpent = prestigeXpSpent,
        pvpKills = pvpKills,
        pvpDeaths = pvpDeaths,
        dailyQuestState = runCatching {
            jsonMapper.readValue(dailyQuestData, DailyQuestState::class.java)
        }.getOrDefault(DailyQuestState()),
        isAkathavae = isAkathavae,
        akathavaePledgedAtMs = akathavaePledgedAtMs,
        akathavaeRenouncedAtMs = akathavaeRenouncedAtMs,
        preAkathavaeClass = preAkathavaeClass,
        arcanum = runCatching {
            jsonMapper.readValue(arcanumData, ArcanumJournal::class.java)
        }.getOrDefault(ArcanumJournal()),
        screenReaderEnabled = screenReaderEnabled,
        audioLinksEnabled = audioLinksEnabled,
        description = description,
        authTokenHash = authTokenHash,
        authTokenIssuedAt = authTokenIssuedAt,
        autolootEnabled = autolootEnabled,
        autopeekEnabled = autopeekEnabled,
        wimpyThresholdPct = wimpyThresholdPct,
        lastRespecAtMs = lastRespecAtMs,
        racialAbilityCooldownUntilMs = racialAbilityCooldownUntilMs,
    )

/** Converts this runtime state to a [PlayerRecord] for persistence. */
fun PlayerState.toPlayerRecord(lastSeenEpochMs: Long): PlayerRecord {
    val pid = playerId ?: error("Cannot persist a PlayerState without a playerId")
    return PlayerRecord(
        id = pid,
        name = name,
        roomId = roomId,
        stats = stats.values,
        gender = gender,
        race = race,
        playerClass = playerClass,
        level = level,
        xpTotal = xpTotal,
        createdAtEpochMs = createdAtEpochMs,
        lastSeenEpochMs = lastSeenEpochMs,
        passwordHash = passwordHash,
        ansiEnabled = ansiEnabled,
        isStaff = isStaff,
        hp = hp,
        mana = mana,
        maxMana = maxMana,
        gold = gold,
        activeQuests = activeQuests,
        completedQuestIds = completedQuestIds,
        dialogueFlags = dialogueFlags.toSet(),
        seenZoneCinematics = seenZoneCinematics.toSet(),
        unlockedAchievementIds = unlockedAchievementIds,
        achievementProgress = achievementProgress,
        activeTitle = activeTitle,
        inbox = inbox.toList(),
        guildId = guildId,
        recallRoomId = recallRoomId,
        lastDeathZone = lastDeathZone,
        lastInnByZone = lastInnByZone.mapValues { (_, v) -> v.value }.toMap(),
        craftingSkills = craftingSkills.toMap(),
        discoveredRecipes = discoveredRecipes.toSet(),
        discoveredFlightPoints = discoveredFlightPoints.toSet(),
        craftingSpecialization = craftingSpecialization,
        factionStandings = factionStandings.toMap(),
        currencies = currencies.toMap(),
        friendsList = friendsList.toSet(),
        bankGold = bankGold,
        bankItems = bankItems.toList(),
        activeSprite = activeSprite,
        mobsKilledTotal = mobsKilledTotal,
        dungeonsCompleted = dungeonsCompleted,
        learnedAbilityIds = learnedAbilityIds.toSet(),
        unlockedClasses = unlockedClasses.toSet(),
        prestigeLevel = prestigeLevel,
        prestigeXpSpent = prestigeXpSpent,
        pvpKills = pvpKills,
        pvpDeaths = pvpDeaths,
        dailyQuestData = jsonMapper.writeValueAsString(dailyQuestState),
        isAkathavae = isAkathavae,
        akathavaePledgedAtMs = akathavaePledgedAtMs,
        akathavaeRenouncedAtMs = akathavaeRenouncedAtMs,
        preAkathavaeClass = preAkathavaeClass,
        arcanumData = jsonMapper.writeValueAsString(arcanum),
        screenReaderEnabled = screenReaderEnabled,
        audioLinksEnabled = audioLinksEnabled,
        description = description,
        authTokenHash = authTokenHash,
        authTokenIssuedAt = authTokenIssuedAt,
        autolootEnabled = autolootEnabled,
        autopeekEnabled = autopeekEnabled,
        wimpyThresholdPct = wimpyThresholdPct,
        lastRespecAtMs = lastRespecAtMs,
        racialAbilityCooldownUntilMs = racialAbilityCooldownUntilMs,
    )
}

/** Clears all guild-related fields on this player. */
fun PlayerState.clearGuild() {
    guildId = null
    guildRank = null
    guildTag = null
}

/** Decreases mob HP by [amount], clamped to 0. */
fun MobState.takeDamage(amount: Int) {
    hp = (hp - amount).coerceAtLeast(0)
}

/** Combines [player] base stats with [equip] bonuses and optional status-effect [mods]. */
fun resolveEffectiveStats(
    player: PlayerState,
    equip: ItemRegistry.EquipmentBonuses,
    mods: StatMap = StatMap.EMPTY,
): StatMap = player.stats + equip.stats + mods

/**
 * Convenience overload that gathers equipment bonuses and status-effect mods
 * from the (possibly null) [items] and [statusEffects] systems, then resolves
 * the player's effective stats in one call.
 *
 * When [classRegistry] is provided, equipment archetypal stats
 * (PRIMARY/SECONDARY/TERTIARY) are resolved against the wearer's class
 * priorities before being merged into effective stats. Callers that don't
 * have the class registry available will lose any archetypal-stat bonuses on
 * equipped items, but won't crash — concrete item stats still apply normally.
 */
fun resolvePlayerStats(
    player: PlayerState,
    items: ItemRegistry?,
    statusEffects: dev.ambon.engine.status.StatusEffectSystem?,
    classRegistry: PlayerClassRegistry? = null,
): StatMap {
    val classDef = classRegistry?.get(player.playerClass)
    val equip = items?.equipmentBonuses(player.sessionId, classDef) ?: ItemRegistry.EquipmentBonuses()
    val mods = statusEffects?.getPlayerStatMods(player.sessionId) ?: StatMap.EMPTY
    return resolveEffectiveStats(player, equip, mods)
}
