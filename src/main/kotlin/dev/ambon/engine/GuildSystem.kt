package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.guild.GuildRank
import dev.ambon.domain.guild.GuildRecord
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.persistence.GuildRepository
import dev.ambon.persistence.PlayerId
import dev.ambon.persistence.PlayerRepository
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
    private val playerRepo: PlayerRepository,
    private val outbound: OutboundBus,
    private val clock: Clock = Clock.systemUTC(),
    private val maxSize: Int = 50,
    private val inviteTimeoutMs: Long = 60_000L,
    private val markPlayerDirty: suspend (SessionId) -> Unit = {},
) {
    private val guildsById = mutableMapOf<String, GuildRecord>()
    private val pendingInvites = mutableMapOf<SessionId, PendingGuildInvite>()

    suspend fun initialize() {
        val all = guildRepo.findAll()
        for (guild in all) {
            guildsById[guild.id] = guild
        }
        log.info { "GuildSystem initialized with ${guildsById.size} guild(s)" }
    }

    fun onPlayerDisconnected(sessionId: SessionId) {
        pendingInvites.remove(sessionId)
    }

    suspend fun onPlayerLogin(sessionId: SessionId) {
        val ps = players.get(sessionId) ?: return
        val gid = ps.guildId ?: return
        val guild = guildsById[gid] ?: run {
            // Guild no longer exists — clear stale reference
            ps.guildId = null
            ps.guildRank = null
            ps.guildTag = null
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
        val ps = players.get(sessionId) ?: return "You are not logged in."
        val pid = ps.playerId ?: return "You are not logged in."

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

    suspend fun disband(sessionId: SessionId): String? {
        val ps = players.get(sessionId) ?: return "You are not logged in."
        val gid = ps.guildId ?: return "You are not in a guild."
        if (ps.guildRank != GuildRank.LEADER) return "Only the guild leader can disband the guild."

        val guild = guildsById[gid] ?: return "Guild not found."

        // Notify and clear all online members
        for ((memberId, _) in guild.members) {
            val memberSid = players.findSessionByPlayerId(memberId)
            if (memberSid != null) {
                val memberPs = players.get(memberSid) ?: continue
                memberPs.guildId = null
                memberPs.guildRank = null
                memberPs.guildTag = null
                markPlayerDirty(memberSid)
                if (memberSid != sessionId) {
                    outbound.send(
                        OutboundEvent.SendInfo(memberSid, "Your guild '${guild.name}' has been disbanded."),
                    )
                }
            } else {
                // Update offline player record
                val record = playerRepo.findById(memberId)
                if (record != null) {
                    playerRepo.save(record.copy(guildId = null))
                }
            }
        }

        guildRepo.delete(gid)
        guildsById.remove(gid)

        outbound.send(OutboundEvent.SendInfo(sessionId, "Guild '${guild.name}' has been disbanded."))
        return null
    }

    suspend fun invite(
        sessionId: SessionId,
        targetName: String,
    ): String? {
        pruneExpiredInvites()
        val ps = players.get(sessionId) ?: return "You are not logged in."
        val gid = ps.guildId ?: return "You are not in a guild."
        val rank = ps.guildRank ?: return "You are not in a guild."
        if (rank == GuildRank.MEMBER) return "Only officers and the leader can send guild invites."

        val guild = guildsById[gid] ?: return "Guild not found."
        if (guild.members.size >= maxSize) return "Your guild is full (max $maxSize members)."

        val targetSid = players.findSessionByName(targetName) ?: return "Player '$targetName' is not online."
        if (targetSid == sessionId) return "You cannot invite yourself."
        val targetPs = players.get(targetSid) ?: return "Player '$targetName' is not online."

        if (targetPs.guildId != null) return "${targetPs.name} is already in a guild."
        if (pendingInvites[targetSid]?.guildId == gid) return "${targetPs.name} already has a pending invite to your guild."

        val expiry = clock.millis() + inviteTimeoutMs
        pendingInvites[targetSid] = PendingGuildInvite(gid, guild.name, ps.name, expiry)

        outbound.send(
            OutboundEvent.SendInfo(
                targetSid,
                "${ps.name} has invited you to join guild '${guild.name}' [${guild.tag}]. Type 'guild accept' to join.",
            ),
        )
        outbound.send(OutboundEvent.SendInfo(sessionId, "Invite sent to ${targetPs.name}."))
        return null
    }

    suspend fun accept(sessionId: SessionId): String? {
        val ps = players.get(sessionId) ?: return "You are not logged in."
        val pid = ps.playerId ?: return "You are not logged in."

        if (ps.guildId != null) return "You are already in a guild."

        val invite = pendingInvites[sessionId] ?: return "You have no pending guild invite."
        pendingInvites.remove(sessionId)

        if (clock.millis() > invite.expiresAtMs) return "Your guild invite has expired."

        val guild = guildsById[invite.guildId] ?: return "That guild no longer exists."
        if (guild.members.size >= maxSize) return "That guild is now full."

        val updated = guild.copy(members = guild.members + (pid to GuildRank.MEMBER))
        guildRepo.save(updated)
        guildsById[guild.id] = updated

        ps.guildId = guild.id
        ps.guildRank = GuildRank.MEMBER
        ps.guildTag = guild.tag
        markPlayerDirty(sessionId)

        outbound.send(OutboundEvent.SendInfo(sessionId, "You have joined guild '${guild.name}' [${guild.tag}]!"))
        broadcastToGuild(updated, "${ps.name} has joined the guild.", excludeSid = sessionId)
        return null
    }

    suspend fun leave(sessionId: SessionId): String? {
        val ps = players.get(sessionId) ?: return "You are not logged in."
        val pid = ps.playerId ?: return "You are not logged in."
        val gid = ps.guildId ?: return "You are not in a guild."
        if (ps.guildRank == GuildRank.LEADER) {
            return "You are the guild leader. Disband the guild with 'guild disband', or promote another member to leader first."
        }

        val guild = guildsById[gid] ?: return "Guild not found."
        val updated = guild.copy(members = guild.members - pid)
        guildRepo.save(updated)
        guildsById[guild.id] = updated

        val guildName = guild.name
        ps.guildId = null
        ps.guildRank = null
        ps.guildTag = null
        markPlayerDirty(sessionId)

        outbound.send(OutboundEvent.SendInfo(sessionId, "You have left guild '$guildName'."))
        broadcastToGuild(updated, "${ps.name} has left the guild.", excludeSid = sessionId)
        return null
    }

    suspend fun kick(
        sessionId: SessionId,
        targetName: String,
    ): String? {
        val ps = players.get(sessionId) ?: return "You are not logged in."
        val gid = ps.guildId ?: return "You are not in a guild."
        val rank = ps.guildRank ?: return "You are not in a guild."
        if (rank == GuildRank.MEMBER) return "Only officers and the leader can kick guild members."

        val guild = guildsById[gid] ?: return "Guild not found."

        val targetEntry = guild.members.entries.find { e ->
            resolveGuildMemberName(e.key)?.equals(targetName, ignoreCase = true) == true
        } ?: return "Player '$targetName' is not a member of your guild."

        val targetPlayerId = targetEntry.key
        val targetRank = targetEntry.value

        if (targetRank == GuildRank.LEADER) return "You cannot kick the guild leader."
        if (rank == GuildRank.OFFICER && targetRank == GuildRank.OFFICER) {
            return "Officers cannot kick other officers."
        }

        val updated = guild.copy(members = guild.members - targetPlayerId)
        guildRepo.save(updated)
        guildsById[guild.id] = updated

        val targetSid = players.findSessionByPlayerId(targetPlayerId)
        if (targetSid != null) {
            val targetPs = players.get(targetSid)
            if (targetPs != null) {
                targetPs.guildId = null
                targetPs.guildRank = null
                targetPs.guildTag = null
                markPlayerDirty(targetSid)
                outbound.send(OutboundEvent.SendInfo(targetSid, "You have been kicked from guild '${guild.name}'."))
            }
        } else {
            val record = playerRepo.findById(targetPlayerId)
            if (record != null) {
                playerRepo.save(record.copy(guildId = null))
            }
        }

        outbound.send(OutboundEvent.SendInfo(sessionId, "$targetName has been kicked from the guild."))
        broadcastToGuild(updated, "$targetName has been kicked from the guild.", excludeSid = sessionId)
        return null
    }

    suspend fun promote(
        sessionId: SessionId,
        targetName: String,
    ): String? {
        val ps = players.get(sessionId) ?: return "You are not logged in."
        val gid = ps.guildId ?: return "You are not in a guild."
        if (ps.guildRank != GuildRank.LEADER) return "Only the guild leader can promote members."

        val guild = guildsById[gid] ?: return "Guild not found."

        val targetEntry = guild.members.entries.find { e ->
            resolveGuildMemberName(e.key)?.equals(targetName, ignoreCase = true) == true
        } ?: return "Player '$targetName' is not a member of your guild."

        val targetPlayerId = targetEntry.key
        val targetRank = targetEntry.value

        if (targetRank == GuildRank.LEADER) return "$targetName is already the leader."
        if (targetRank == GuildRank.OFFICER) return "$targetName is already an officer."

        val updated = guild.copy(members = guild.members + (targetPlayerId to GuildRank.OFFICER))
        guildRepo.save(updated)
        guildsById[guild.id] = updated

        val targetSid = players.findSessionByPlayerId(targetPlayerId)
        if (targetSid != null) {
            val targetPs = players.get(targetSid)
            targetPs?.guildRank = GuildRank.OFFICER
            outbound.send(OutboundEvent.SendInfo(targetSid, "You have been promoted to Officer in guild '${guild.name}'."))
        }

        outbound.send(OutboundEvent.SendInfo(sessionId, "$targetName has been promoted to Officer."))
        return null
    }

    suspend fun demote(
        sessionId: SessionId,
        targetName: String,
    ): String? {
        val ps = players.get(sessionId) ?: return "You are not logged in."
        val gid = ps.guildId ?: return "You are not in a guild."
        if (ps.guildRank != GuildRank.LEADER) return "Only the guild leader can demote members."

        val guild = guildsById[gid] ?: return "Guild not found."

        val targetEntry = guild.members.entries.find { e ->
            resolveGuildMemberName(e.key)?.equals(targetName, ignoreCase = true) == true
        } ?: return "Player '$targetName' is not a member of your guild."

        val targetPlayerId = targetEntry.key
        val targetRank = targetEntry.value

        if (targetRank == GuildRank.LEADER) return "You cannot demote the guild leader."
        if (targetRank == GuildRank.MEMBER) return "$targetName is already a Member."

        val updated = guild.copy(members = guild.members + (targetPlayerId to GuildRank.MEMBER))
        guildRepo.save(updated)
        guildsById[guild.id] = updated

        val targetSid = players.findSessionByPlayerId(targetPlayerId)
        if (targetSid != null) {
            val targetPs = players.get(targetSid)
            targetPs?.guildRank = GuildRank.MEMBER
            outbound.send(OutboundEvent.SendInfo(targetSid, "You have been demoted to Member in guild '${guild.name}'."))
        }

        outbound.send(OutboundEvent.SendInfo(sessionId, "$targetName has been demoted to Member."))
        return null
    }

    suspend fun setMotd(
        sessionId: SessionId,
        motd: String,
    ): String? {
        val ps = players.get(sessionId) ?: return "You are not logged in."
        val gid = ps.guildId ?: return "You are not in a guild."
        val rank = ps.guildRank ?: return "You are not in a guild."
        if (rank == GuildRank.MEMBER) return "Only officers and the leader can set the guild MOTD."

        val guild = guildsById[gid] ?: return "Guild not found."
        val updated = guild.copy(motd = motd.trim().ifEmpty { null })
        guildRepo.save(updated)
        guildsById[guild.id] = updated

        outbound.send(OutboundEvent.SendInfo(sessionId, "Guild MOTD updated."))
        return null
    }

    suspend fun roster(sessionId: SessionId): String? {
        val ps = players.get(sessionId) ?: return "You are not logged in."
        val gid = ps.guildId ?: return "You are not in a guild."
        val guild = guildsById[gid] ?: return "Guild not found."

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
        return null
    }

    suspend fun info(sessionId: SessionId): String? {
        val ps = players.get(sessionId) ?: return "You are not logged in."
        val gid = ps.guildId ?: return "You are not in a guild."
        val guild = guildsById[gid] ?: return "Guild not found."

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
        return null
    }

    suspend fun gchat(
        sessionId: SessionId,
        message: String,
    ): String? {
        val ps = players.get(sessionId) ?: return "You are not logged in."
        val gid = ps.guildId ?: return "You are not in a guild."
        val guild = guildsById[gid] ?: return "Guild not found."

        pruneExpiredInvites()

        val formatted = "[${guild.tag}] ${ps.name}: $message"
        for ((memberId, _) in guild.members) {
            val sid = players.findSessionByPlayerId(memberId) ?: continue
            outbound.send(OutboundEvent.SendInfo(sid, formatted))
        }
        return null
    }

    // -------- helpers --------

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
        return if (sid != null) players.get(sid)?.name else playerRepo.findById(playerId)?.name
    }

    private fun pruneExpiredInvites() {
        val now = clock.millis()
        pendingInvites.entries.removeIf { it.value.expiresAtMs < now }
    }
}
