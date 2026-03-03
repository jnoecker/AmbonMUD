package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.guild.GuildRank
import dev.ambon.domain.guild.GuildRecord
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.persistence.GuildRepository
import dev.ambon.persistence.PlayerId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock

private val log = KotlinLogging.logger {}

data class PendingGuildInvite(
    val guildId: String,
    val guildName: String,
    val inviterName: String,
    val expiresAtMs: Long,
)

class GuildSystem(
    private val players: PlayerRegistry,
    private val guildRepo: GuildRepository,
    private val outbound: OutboundBus,
    private val clock: Clock = Clock.systemUTC(),
    private val maxSize: Int = 50,
    private val inviteTimeoutMs: Long = 60_000L,
    private val markPlayerDirty: suspend (SessionId) -> Unit = {},
) : GameSystem {
    private val guildsById = mutableMapOf<String, GuildRecord>()
    private val pendingInvites = mutableMapOf<SessionId, PendingGuildInvite>()

    suspend fun initialize() {
        val all = guildRepo.findAll()
        for (guild in all) {
            guildsById[guild.id] = guild
        }
        log.info { "GuildSystem initialized with ${guildsById.size} guild(s)" }
    }

    override suspend fun onPlayerDisconnected(sessionId: SessionId) {
        pendingInvites.remove(sessionId)
    }

    suspend fun onPlayerLogin(sessionId: SessionId) {
        val ps = players.get(sessionId) ?: return
        val gid = ps.guildId ?: return
        val guild = guildsById[gid] ?: run {
            // Guild no longer exists — clear stale reference
            ps.clearGuild()
            markPlayerDirty(sessionId)
            return
        }
        val playerId = ps.playerId ?: return
        ps.guildRank = guild.members[playerId]
        ps.guildTag = guild.tag
    }

    suspend fun create(
        sessionId: SessionId,
        name: String,
        tag: String,
    ): String? {
        val ps = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        val pid = ps.playerId ?: return ERR_NOT_CONNECTED

        if (ps.guildId != null) return "You are already in a guild."

        val trimName = name.trim()
        if (trimName.length < 3 || trimName.length > 32) return "Guild name must be 3–32 characters."
        if (!trimName.matches(Regex("[A-Za-z][A-Za-z0-9 \\-]*"))) {
            return "Guild name may only contain letters, spaces, and hyphens, and must start with a letter."
        }

        val trimTag = tag.trim().uppercase()
        if (trimTag.length < 2 || trimTag.length > 5) return "Guild tag must be 2–5 characters."
        if (!trimTag.matches(Regex("[A-Z0-9]+"))) return "Guild tag may only contain letters and digits."

        // Check uniqueness
        if (guildsById.values.any { it.name.equals(trimName, ignoreCase = true) }) {
            return "A guild named '$trimName' already exists."
        }
        if (guildsById.values.any { it.tag.equals(trimTag, ignoreCase = true) }) {
            return "Guild tag '$trimTag' is already in use."
        }

        val guildId = trimName.lowercase().replace(Regex("[^a-z0-9]"), "_")
        if (guildsById.containsKey(guildId)) return "A guild with a conflicting name already exists."
        val record =
            GuildRecord(
                id = guildId,
                name = trimName,
                tag = trimTag,
                leaderId = pid,
                members = mapOf(pid to GuildRank.LEADER),
                createdAtEpochMs = clock.millis(),
            )

        guildRepo.create(record)
        guildsById[guildId] = record

        ps.guildId = guildId
        ps.guildRank = GuildRank.LEADER
        ps.guildTag = trimTag
        markPlayerDirty(sessionId)

        outbound.send(OutboundEvent.SendInfo(sessionId, "Guild '$trimName' [$trimTag] founded!"))
        return null
    }

    suspend fun disband(sessionId: SessionId): String? = withMembership(sessionId) { ps, guild ->
        if (ps.guildRank != GuildRank.LEADER) return@withMembership "Only the guild leader can disband the guild."

        // Notify and clear all online members
        for ((memberId, _) in guild.members) {
            val memberSid = players.findSessionByPlayerId(memberId)
            if (memberSid != null) {
                val memberPs = players.get(memberSid) ?: continue
                memberPs.clearGuild()
                markPlayerDirty(memberSid)
                if (memberSid != sessionId) {
                    outbound.send(
                        OutboundEvent.SendInfo(memberSid, "Your guild '${guild.name}' has been disbanded."),
                    )
                }
            } else {
                players.updateOfflinePlayer(memberId) { it.copy(guildId = null) }
            }
        }

        guildRepo.delete(guild.id)
        guildsById.remove(guild.id)

        outbound.send(OutboundEvent.SendInfo(sessionId, "Guild '${guild.name}' has been disbanded."))
        null
    }

    suspend fun invite(
        sessionId: SessionId,
        targetName: String,
    ): String? {
        pruneExpiredInvites()
        return withMembership(sessionId) { ps, guild ->
            val rank = ps.guildRank ?: return@withMembership "You are not in a guild."
            if (rank == GuildRank.MEMBER) return@withMembership "Only officers and the leader can send guild invites."
            if (guild.members.size >= maxSize) return@withMembership "Your guild is full (max $maxSize members)."

            val targetSid = players.findSessionByName(targetName)
                ?: return@withMembership "Player '$targetName' is not online."
            if (targetSid == sessionId) return@withMembership "You cannot invite yourself."
            val targetPs = players.get(targetSid) ?: return@withMembership "Player '$targetName' is not online."

            if (targetPs.guildId != null) return@withMembership "${targetPs.name} is already in a guild."
            if (pendingInvites[targetSid]?.guildId == guild.id) {
                return@withMembership "${targetPs.name} already has a pending invite to your guild."
            }

            val expiry = clock.millis() + inviteTimeoutMs
            pendingInvites[targetSid] = PendingGuildInvite(guild.id, guild.name, ps.name, expiry)

            outbound.send(
                OutboundEvent.SendInfo(
                    targetSid,
                    "${ps.name} has invited you to join guild '${guild.name}' [${guild.tag}]. Type 'guild accept' to join.",
                ),
            )
            outbound.send(OutboundEvent.SendInfo(sessionId, "Invite sent to ${targetPs.name}."))
            null
        }
    }

    suspend fun accept(sessionId: SessionId): String? {
        val ps = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        val pid = ps.playerId ?: return ERR_NOT_CONNECTED

        if (ps.guildId != null) return "You are already in a guild."

        val invite = pendingInvites[sessionId] ?: return "You have no pending guild invite."
        pendingInvites.remove(sessionId)

        if (clock.millis() >= invite.expiresAtMs) return "Your guild invite has expired."

        val guild = guildsById[invite.guildId] ?: return "That guild no longer exists."
        if (guild.members.size >= maxSize) return "That guild is now full."

        val updated = guild.copy(members = guild.members + (pid to GuildRank.MEMBER))
        persistGuild(updated)

        ps.guildId = guild.id
        ps.guildRank = GuildRank.MEMBER
        ps.guildTag = guild.tag
        markPlayerDirty(sessionId)

        outbound.send(OutboundEvent.SendInfo(sessionId, "You have joined guild '${guild.name}' [${guild.tag}]!"))
        broadcastToGuild(updated, "${ps.name} has joined the guild.", excludeSid = sessionId)
        return null
    }

    suspend fun leave(sessionId: SessionId): String? = withMembership(sessionId) { ps, guild ->
        val pid = ps.playerId ?: return@withMembership ERR_NOT_CONNECTED
        if (ps.guildRank == GuildRank.LEADER) {
            return@withMembership "You are the guild leader. Disband the guild with 'guild disband', " +
                "or promote another member to leader first."
        }

        val updated = guild.copy(members = guild.members - pid)
        persistGuild(updated)

        val guildName = guild.name
        ps.clearGuild()
        markPlayerDirty(sessionId)

        outbound.send(OutboundEvent.SendInfo(sessionId, "You have left guild '$guildName'."))
        broadcastToGuild(updated, "${ps.name} has left the guild.", excludeSid = sessionId)
        null
    }

    suspend fun kick(
        sessionId: SessionId,
        targetName: String,
    ): String? = withMembership(sessionId) { ps, guild ->
        val rank = ps.guildRank ?: return@withMembership "You are not in a guild."
        if (rank == GuildRank.MEMBER) return@withMembership "Only officers and the leader can kick guild members."

        val (targetPlayerId, targetRank) = findMemberByName(guild, targetName)
            ?: return@withMembership "Player '$targetName' is not a member of your guild."

        if (targetRank == GuildRank.LEADER) return@withMembership "You cannot kick the guild leader."
        if (rank == GuildRank.OFFICER && targetRank == GuildRank.OFFICER) {
            return@withMembership "Officers cannot kick other officers."
        }

        val updated = guild.copy(members = guild.members - targetPlayerId)
        persistGuild(updated)

        val targetSid = players.findSessionByPlayerId(targetPlayerId)
        if (targetSid != null) {
            val targetPs = players.get(targetSid)
            if (targetPs != null) {
                targetPs.clearGuild()
                markPlayerDirty(targetSid)
                outbound.send(OutboundEvent.SendInfo(targetSid, "You have been kicked from guild '${guild.name}'."))
            }
        } else {
            players.updateOfflinePlayer(targetPlayerId) { it.copy(guildId = null) }
        }

        outbound.send(OutboundEvent.SendInfo(sessionId, "$targetName has been kicked from the guild."))
        broadcastToGuild(updated, "$targetName has been kicked from the guild.", excludeSid = sessionId)
        null
    }

    suspend fun promote(
        sessionId: SessionId,
        targetName: String,
    ): String? = withMembership(sessionId) { ps, guild ->
        if (ps.guildRank != GuildRank.LEADER) return@withMembership "Only the guild leader can promote members."

        val (targetPlayerId, targetRank) = findMemberByName(guild, targetName)
            ?: return@withMembership "Player '$targetName' is not a member of your guild."

        if (targetRank == GuildRank.LEADER) return@withMembership "$targetName is already the leader."
        if (targetRank == GuildRank.OFFICER) return@withMembership "$targetName is already an officer."

        val updated = guild.copy(members = guild.members + (targetPlayerId to GuildRank.OFFICER))
        persistGuild(updated)

        val targetSid = players.findSessionByPlayerId(targetPlayerId)
        if (targetSid != null) {
            val targetPs = players.get(targetSid)
            targetPs?.guildRank = GuildRank.OFFICER
            outbound.send(OutboundEvent.SendInfo(targetSid, "You have been promoted to Officer in guild '${guild.name}'."))
        }

        outbound.send(OutboundEvent.SendInfo(sessionId, "$targetName has been promoted to Officer."))
        null
    }

    suspend fun demote(
        sessionId: SessionId,
        targetName: String,
    ): String? = withMembership(sessionId) { ps, guild ->
        if (ps.guildRank != GuildRank.LEADER) return@withMembership "Only the guild leader can demote members."

        val (targetPlayerId, targetRank) = findMemberByName(guild, targetName)
            ?: return@withMembership "Player '$targetName' is not a member of your guild."

        if (targetRank == GuildRank.LEADER) return@withMembership "You cannot demote the guild leader."
        if (targetRank == GuildRank.MEMBER) return@withMembership "$targetName is already a Member."

        val updated = guild.copy(members = guild.members + (targetPlayerId to GuildRank.MEMBER))
        persistGuild(updated)

        val targetSid = players.findSessionByPlayerId(targetPlayerId)
        if (targetSid != null) {
            val targetPs = players.get(targetSid)
            targetPs?.guildRank = GuildRank.MEMBER
            outbound.send(OutboundEvent.SendInfo(targetSid, "You have been demoted to Member in guild '${guild.name}'."))
        }

        outbound.send(OutboundEvent.SendInfo(sessionId, "$targetName has been demoted to Member."))
        null
    }

    suspend fun setMotd(
        sessionId: SessionId,
        motd: String,
    ): String? = withMembership(sessionId) { ps, guild ->
        val rank = ps.guildRank ?: return@withMembership "You are not in a guild."
        if (rank == GuildRank.MEMBER) return@withMembership "Only officers and the leader can set the guild MOTD."

        val updated = guild.copy(motd = motd.trim().ifEmpty { null })
        persistGuild(updated)

        outbound.send(OutboundEvent.SendInfo(sessionId, "Guild MOTD updated."))
        null
    }

    suspend fun roster(sessionId: SessionId): String? = withMembership(sessionId) { _, guild ->
        val lines = buildString {
            appendLine("=== ${guild.name} [${guild.tag}] ===")
            appendLine("Members (${guild.members.size}/$maxSize):")
            for ((memberId, rank) in guild.members.entries.sortedBy { it.value.ordinal }) {
                val sid = players.findSessionByPlayerId(memberId)
                val onlineName = if (sid != null) players.get(sid)?.name else null
                val nameStr = onlineName ?: "(offline #${memberId.value})"
                val onlineMarker = if (onlineName != null) " *" else ""
                append("  [${rank.name.padEnd(7)}] $nameStr$onlineMarker")
                if (memberId == guild.leaderId) append(" (Leader)")
                appendLine()
            }
            if (guild.motd != null) {
                append("MOTD: ${guild.motd}")
            }
        }.trimEnd()

        outbound.send(OutboundEvent.SendInfo(sessionId, lines))
        null
    }

    suspend fun info(sessionId: SessionId): String? = withMembership(sessionId) { _, guild ->
        val leaderSid = players.findSessionByPlayerId(guild.leaderId)
        val leaderName = if (leaderSid != null) players.get(leaderSid)?.name else null

        val lines = buildString {
            appendLine("Guild: ${guild.name} [${guild.tag}]")
            appendLine("Leader: ${leaderName ?: "(offline)"}")
            appendLine("Members: ${guild.members.size}/$maxSize")
            if (guild.motd != null) {
                append("MOTD: ${guild.motd}")
            }
        }.trimEnd()

        outbound.send(OutboundEvent.SendInfo(sessionId, lines))
        null
    }

    suspend fun gchat(
        sessionId: SessionId,
        message: String,
    ): String? = withMembership(sessionId) { ps, guild ->
        pruneExpiredInvites()

        val formatted = "[${guild.tag}] ${ps.name}: $message"
        for ((memberId, _) in guild.members) {
            val sid = players.findSessionByPlayerId(memberId) ?: continue
            outbound.send(OutboundEvent.SendInfo(sid, formatted))
        }
        null
    }

    // -------- helpers --------

    private suspend fun withMembership(
        sessionId: SessionId,
        block: suspend (PlayerState, GuildRecord) -> String?,
    ): String? {
        val ps = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        val gid = ps.guildId ?: return "You are not in a guild."
        val guild = guildsById[gid] ?: return "Guild not found."
        return block(ps, guild)
    }

    private suspend fun findMemberByName(
        guild: GuildRecord,
        targetName: String,
    ): Pair<PlayerId, GuildRank>? =
        guild.members.entries.find { (playerId, _) ->
            resolveGuildMemberName(playerId)?.equals(targetName, ignoreCase = true) == true
        }?.let { it.key to it.value }

    private suspend fun persistGuild(updated: GuildRecord) {
        guildRepo.save(updated)
        guildsById[updated.id] = updated
    }

    private suspend fun broadcastToGuild(
        guild: GuildRecord,
        message: String,
        excludeSid: SessionId? = null,
    ) {
        for ((memberId, _) in guild.members) {
            val sid = players.findSessionByPlayerId(memberId) ?: continue
            if (sid == excludeSid) continue
            outbound.send(OutboundEvent.SendInfo(sid, message))
        }
    }

    private suspend fun resolveGuildMemberName(playerId: PlayerId): String? {
        val sid = players.findSessionByPlayerId(playerId)
        return if (sid != null) players.get(sid)?.name else players.findOfflinePlayerName(playerId)
    }

    private fun pruneExpiredInvites() {
        val now = clock.millis()
        pendingInvites.entries.removeIf { it.value.expiresAtMs <= now }
    }
}
