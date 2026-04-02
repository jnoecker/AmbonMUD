package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock

private val log = KotlinLogging.logger {}

/** A pending duel challenge awaiting acceptance. */
data class DuelChallenge(
    val challengerSid: SessionId,
    val targetSid: SessionId,
    val createdAtMs: Long,
)

/** An active duel between two players. */
data class ActiveDuel(
    val player1: SessionId,
    val player2: SessionId,
    val startedAtMs: Long,
    var lastTickedAtMs: Long = 0L,
)

/**
 * Manages PvP duel challenges and active duels.
 *
 * Flow: challenger sends `duel <player>` → target receives challenge →
 * target accepts with `duel accept` → combat begins. Either player can
 * `duel decline` or `flee` to end the duel. The loser's HP is set to 1
 * (not killed). Challenges expire after 30 seconds.
 */
class DuelSystem(
    private val clock: Clock,
    private val challengeTimeoutMs: Long = 30_000L,
) {
    private val pendingChallenges = mutableMapOf<SessionId, DuelChallenge>()
    private val activeDuels = mutableMapOf<SessionId, ActiveDuel>()

    fun getPendingChallenge(targetSid: SessionId): DuelChallenge? = pendingChallenges[targetSid]

    fun isInDuel(sid: SessionId): Boolean = sid in activeDuels

    fun getDuel(sid: SessionId): ActiveDuel? = activeDuels[sid]

    fun getOpponent(sid: SessionId): SessionId? {
        val duel = activeDuels[sid] ?: return null
        return if (duel.player1 == sid) duel.player2 else duel.player1
    }

    /**
     * Creates a pending duel challenge. Returns error message or null on success.
     */
    fun challenge(challengerSid: SessionId, targetSid: SessionId): String? {
        if (challengerSid == targetSid) return "You cannot duel yourself."
        if (isInDuel(challengerSid)) return "You are already in a duel."
        if (isInDuel(targetSid)) return "That player is already in a duel."
        if (pendingChallenges[targetSid]?.challengerSid == challengerSid) {
            return "You already challenged that player."
        }

        // Expire stale challenges first
        expireChallenges()

        pendingChallenges[targetSid] = DuelChallenge(
            challengerSid = challengerSid,
            targetSid = targetSid,
            createdAtMs = clock.millis(),
        )
        log.debug { "Duel challenge: $challengerSid → $targetSid" }
        return null
    }

    /**
     * Target accepts a pending challenge. Returns the ActiveDuel or null
     * if no valid challenge exists.
     */
    fun accept(targetSid: SessionId): ActiveDuel? {
        val challenge = pendingChallenges.remove(targetSid) ?: return null
        if (clock.millis() - challenge.createdAtMs > challengeTimeoutMs) return null

        val duel = ActiveDuel(
            player1 = challenge.challengerSid,
            player2 = challenge.targetSid,
            startedAtMs = clock.millis(),
        )
        activeDuels[duel.player1] = duel
        activeDuels[duel.player2] = duel
        log.debug { "Duel started: ${duel.player1} vs ${duel.player2}" }
        return duel
    }

    /** Target declines a pending challenge. Returns the challenge or null. */
    fun decline(targetSid: SessionId): DuelChallenge? = pendingChallenges.remove(targetSid)

    /** Ends an active duel. Returns the duel or null. */
    fun endDuel(sid: SessionId): ActiveDuel? {
        val duel = activeDuels.remove(sid) ?: return null
        val other = if (duel.player1 == sid) duel.player2 else duel.player1
        activeDuels.remove(other)
        log.debug { "Duel ended: ${duel.player1} vs ${duel.player2}" }
        return duel
    }

    /** Cleans up on disconnect: cancels challenges and ends active duels. */
    fun onPlayerDisconnect(sid: SessionId): ActiveDuel? {
        // Remove any pending challenges involving this player
        pendingChallenges.entries.removeIf { (_, c) ->
            c.challengerSid == sid || c.targetSid == sid
        }
        return endDuel(sid)
    }

    /** Expires old challenges. Called periodically. */
    fun expireChallenges() {
        val now = clock.millis()
        pendingChallenges.entries.removeIf { (_, c) ->
            now - c.createdAtMs > challengeTimeoutMs
        }
    }

    /** Returns all active duels (may contain duplicates — each duel stored under both players). */
    fun activeDuels(): List<ActiveDuel> = activeDuels.values.distinct()

    fun clear() {
        pendingChallenges.clear()
        activeDuels.clear()
    }
}
