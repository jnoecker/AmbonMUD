package dev.ambon.engine

import dev.ambon.domain.StatMap
import dev.ambon.domain.achievement.AchievementState
import dev.ambon.domain.crafting.CraftingSkillState
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mail.MailMessage
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.quest.QuestState
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.PlayerId
import dev.ambon.persistence.PlayerRecord

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
    val passwordHash: String = "",
    var activeQuests: Map<String, QuestState> = emptyMap(),
    var completedQuestIds: Set<String> = emptySet(),
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
    var friendsList: MutableSet<String> = mutableSetOf(),
    /** Epoch-ms timestamp after which recall is available again. Runtime-only; not persisted. */
    var recallCooldownUntilMs: Long = 0L,
    var craftingSkills: MutableMap<String, CraftingSkillState> = mutableMapOf(),
    /** Epoch-ms timestamp after which gathering is available again. Runtime-only; not persisted. */
    var gatherCooldownUntilMs: Long = 0L,
) {
    data class MailComposeState(
        val recipientName: String,
        val lines: MutableList<String> = mutableListOf(),
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
        stats = StatMap.of(
            "STR" to strength,
            "DEX" to dexterity,
            "CON" to constitution,
            "INT" to intelligence,
            "WIS" to wisdom,
            "CHA" to charisma,
        ),
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
        unlockedAchievementIds = unlockedAchievementIds,
        achievementProgress = achievementProgress,
        activeTitle = activeTitle,
        inbox = inbox.toMutableList(),
        guildId = guildId,
        recallRoomId = recallRoomId,
        craftingSkills = craftingSkills.map { (key, state) ->
            key.lowercase() to state
        }.toMap().toMutableMap(),
        friendsList = friendsList.toMutableSet(),
    )

/** Converts this runtime state to a [PlayerRecord] for persistence. */
fun PlayerState.toPlayerRecord(lastSeenEpochMs: Long): PlayerRecord {
    val pid = playerId ?: error("Cannot persist a PlayerState without a playerId")
    return PlayerRecord(
        id = pid,
        name = name,
        roomId = roomId,
        strength = stats["STR"],
        dexterity = stats["DEX"],
        constitution = stats["CON"],
        intelligence = stats["INT"],
        wisdom = stats["WIS"],
        charisma = stats["CHA"],
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
        unlockedAchievementIds = unlockedAchievementIds,
        achievementProgress = achievementProgress,
        activeTitle = activeTitle,
        inbox = inbox.toList(),
        guildId = guildId,
        recallRoomId = recallRoomId,
        craftingSkills = craftingSkills.toMap(),
        friendsList = friendsList.toSet(),
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
 */
fun resolvePlayerStats(
    player: PlayerState,
    items: ItemRegistry?,
    statusEffects: dev.ambon.engine.status.StatusEffectSystem?,
): StatMap {
    val equip = items?.equipmentBonuses(player.sessionId) ?: ItemRegistry.EquipmentBonuses()
    val mods = statusEffects?.getPlayerStatMods(player.sessionId) ?: StatMap.EMPTY
    return resolveEffectiveStats(player, equip, mods)
}
