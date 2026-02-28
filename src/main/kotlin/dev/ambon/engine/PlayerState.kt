package dev.ambon.engine

import dev.ambon.domain.achievement.AchievementState
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mail.MailMessage
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.quest.QuestState
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatModifiers
import dev.ambon.persistence.PlayerId

data class PlayerState(
    val sessionId: SessionId,
    var name: String,
    var roomId: RoomId,
    var playerId: PlayerId? = null,
    var baseMaxHp: Int = BASE_MAX_HP,
    var hp: Int = BASE_MAX_HP,
    var maxHp: Int = BASE_MAX_HP,
    var strength: Int = BASE_STAT,
    var dexterity: Int = BASE_STAT,
    var constitution: Int = BASE_STAT,
    var intelligence: Int = BASE_STAT,
    var wisdom: Int = BASE_STAT,
    var charisma: Int = BASE_STAT,
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
    var recallRoomId: RoomId? = null,
    /** Epoch-ms timestamp after which recall is available again. Runtime-only; not persisted. */
    var recallCooldownUntilMs: Long = 0L,
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
            "baseMaxHp=$baseMaxHp, hp=$hp, maxHp=$maxHp, " +
            "strength=$strength, dexterity=$dexterity, constitution=$constitution, " +
            "intelligence=$intelligence, wisdom=$wisdom, charisma=$charisma, " +
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

/** Decreases HP by [amount], clamped to 0. */
fun PlayerState.takeDamage(amount: Int) {
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

/** Decreases mana by [amount], clamped to 0. */
fun PlayerState.spendMana(amount: Int) {
    mana = (mana - amount).coerceAtLeast(0)
}

/** Decreases mob HP by [amount], clamped to 0. */
fun MobState.takeDamage(amount: Int) {
    hp = (hp - amount).coerceAtLeast(0)
}

/** Resolved stat totals for a player: base + equipment bonuses + status-effect modifiers. */
data class EffectiveStats(
    val str: Int,
    val dex: Int,
    val con: Int,
    val int: Int,
    val wis: Int,
    val cha: Int,
)

/** Combines [player] base stats with [equip] bonuses and optional status-effect [mods]. */
fun resolveEffectiveStats(
    player: PlayerState,
    equip: ItemRegistry.EquipmentBonuses,
    mods: StatModifiers = StatModifiers.ZERO,
): EffectiveStats =
    EffectiveStats(
        str = player.strength + equip.strength + mods.str,
        dex = player.dexterity + equip.dexterity + mods.dex,
        con = player.constitution + equip.constitution + mods.con,
        int = player.intelligence + equip.intelligence + mods.int,
        wis = player.wisdom + equip.wisdom + mods.wis,
        cha = player.charisma + equip.charisma + mods.cha,
    )
