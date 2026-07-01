package dev.ambon.engine

import dev.ambon.config.AutoQuestsConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.ids.idZone
import dev.ambon.domain.world.World
import java.time.Clock
import kotlin.random.Random

/**
 * An auto-generated timed kill quest that players can request on demand.
 */
data class AutoQuest(
    val sessionId: SessionId,
    val targetMobName: String,
    val targetMobTemplateId: String,
    val killsRequired: Int,
    var killsCompleted: Int = 0,
    val rewardGold: Long,
    val rewardXp: Long,
    val expiresAtMs: Long,
)

/**
 * Manages auto-generated repeatable bounty quests.
 *
 * Players can request a random kill quest targeting mobs in their current zone.
 * Each quest has a time limit and a cooldown before the next quest can be requested.
 */
class AutoQuestSystem(
    private val config: AutoQuestsConfig,
    private val world: World,
    private val players: PlayerRegistry,
    private val clock: Clock,
    private val random: Random = Random.Default,
) : GameSystem {
    private val scoped = SessionScoped()

    /** Active auto-quest per session (at most one). */
    private val activeQuests = scoped.map<AutoQuest>()

    /** Epoch-ms timestamp after which a player may request again. */
    private val cooldowns = scoped.map<Long>()

    /**
     * Generates and assigns a random kill quest based on mobs in the player's
     * current zone (or nearby zones if the current zone has no mob spawns).
     *
     * Returns the generated [AutoQuest] on success, or an error string on failure.
     */
    fun requestQuest(sessionId: SessionId): Result<AutoQuest> {
        if (!config.enabled) {
            return Result.failure(AutoQuestError("Bounty quests are not available."))
        }

        if (activeQuests.containsKey(sessionId)) {
            return Result.failure(AutoQuestError("You already have an active bounty. Use 'bounty info' to check progress."))
        }

        val now = clock.millis()
        val cooldownUntil = cooldowns[sessionId] ?: 0L
        if (now < cooldownUntil) {
            val remaining = (cooldownUntil - now + 999) / 1000
            return Result.failure(AutoQuestError("You must wait ${remaining}s before requesting another bounty."))
        }

        val player = players.get(sessionId)
            ?: return Result.failure(AutoQuestError("Player not found."))

        val playerZone = player.roomId.zone
        // How many placements each template has in the world. Used to cap the
        // requested kill count so we never ask players to kill a unique mob
        // (a single placement) more times than it can ever appear. See #1097.
        val spawnCounts = world.mobSpawns.groupingBy { it.templateId }.eachCount()
        val candidates = world.mobSpawns
            .asSequence()
            .filter { idZone(it.id.value) == playerZone }
            .map { it.templateId }
            .distinct()
            .mapNotNull { world.mobTemplate(it) }
            .filter { it.dialogue == null && it.xpReward > 0 }
            .toList()
        if (candidates.isEmpty()) {
            return Result.failure(AutoQuestError("No suitable targets found in this area."))
        }

        val mob = candidates[random.nextInt(candidates.size)]
        val spawnCount = spawnCounts[mob.id] ?: 1
        val killCount = random.nextInt(config.killCountMin, config.killCountMax + 1)
            .coerceAtMost(spawnCount.coerceAtLeast(1))
        val rewardGold = config.rewardGoldBase + config.rewardGoldPerLevel * player.level
        val rewardXp = config.rewardXpBase + config.rewardXpPerLevel * player.level

        val quest = AutoQuest(
            sessionId = sessionId,
            targetMobName = mob.name,
            targetMobTemplateId = mob.id.value,
            killsRequired = killCount,
            rewardGold = rewardGold,
            rewardXp = rewardXp,
            expiresAtMs = now + config.timeLimitMs,
        )
        activeQuests[sessionId] = quest
        return Result.success(quest)
    }

    /**
     * Checks active quests for expiration. Returns the list of expired quest session IDs.
     */
    fun tick(nowMs: Long): List<SessionId> {
        val expired = mutableListOf<SessionId>()
        val iter = activeQuests.iterator()
        while (iter.hasNext()) {
            val (sid, quest) = iter.next()
            if (nowMs >= quest.expiresAtMs) {
                iter.remove()
                expired.add(sid)
            }
        }
        return expired
    }

    /**
     * Called when a player kills a mob. Increments progress if the mob matches
     * the active bounty target.
     *
     * Returns `true` if the quest is now complete (kills met).
     */
    fun onMobKill(sessionId: SessionId, mobTemplateId: String): Boolean {
        val quest = activeQuests[sessionId] ?: return false
        if (quest.targetMobTemplateId != mobTemplateId) return false
        quest.killsCompleted++
        return quest.killsCompleted >= quest.killsRequired
    }

    /**
     * Completes the quest: removes it from active quests and sets cooldown.
     * The caller is responsible for granting rewards and sending messages.
     */
    fun completeQuest(sessionId: SessionId): AutoQuest? {
        val quest = activeQuests.remove(sessionId) ?: return null
        cooldowns[sessionId] = clock.millis() + config.cooldownMs
        return quest
    }

    /** Returns the active auto-quest for the session, or null. */
    fun getActiveQuest(sessionId: SessionId): AutoQuest? = activeQuests[sessionId]

    /** Cancels the active auto-quest. Returns true if there was one. */
    fun abandonQuest(sessionId: SessionId): Boolean {
        val removed = activeQuests.remove(sessionId) != null
        if (removed) {
            cooldowns[sessionId] = clock.millis() + config.cooldownMs
        }
        return removed
    }

    /** Cleans up when a player disconnects. */
    override suspend fun onPlayerDisconnected(sessionId: SessionId) = scoped.clear(sessionId)

    override fun remapSession(oldSid: SessionId, newSid: SessionId) {
        scoped.remap(oldSid, newSid)
        // The moved AutoQuest still carries the old session id in its body; fix it up.
        activeQuests[newSid]?.let {
            activeQuests[newSid] = it.copy(sessionId = newSid)
        }
    }

    /**
     * Returns the time remaining in milliseconds for the active quest, or null.
     */
    fun timeRemainingMs(sessionId: SessionId): Long? {
        val quest = activeQuests[sessionId] ?: return null
        return (quest.expiresAtMs - clock.millis()).coerceAtLeast(0)
    }
}

/** Typed error for auto-quest failures. */
class AutoQuestError(
    message: String,
) : Exception(message)
