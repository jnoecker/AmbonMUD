package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.PlayerClass
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import java.time.Clock
import java.util.UUID

data class Group(
    val id: UUID,
    var leader: SessionId,
    val members: MutableList<SessionId>,
    var lootRobinIndex: Int = 0,
)

data class PendingInvite(
    val inviterSid: SessionId,
    val inviteeSid: SessionId,
    val expiresAtMs: Long,
)

class GroupSystem(
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val clock: Clock = Clock.systemUTC(),
    private val maxGroupSize: Int = 5,
    private val inviteTimeoutMs: Long = 60_000L,
) {
    private val groupBySession = mutableMapOf<SessionId, Group>()
    private val pendingInvites = mutableMapOf<SessionId, PendingInvite>()

    fun getGroup(sessionId: SessionId): Group? = groupBySession[sessionId]

    fun isGrouped(sessionId: SessionId): Boolean = groupBySession.containsKey(sessionId)

    fun isLeader(sessionId: SessionId): Boolean {
        val group = groupBySession[sessionId] ?: return false
        return group.leader == sessionId
    }

    fun groupMembers(sessionId: SessionId): List<SessionId> =
        groupBySession[sessionId]
            ?.members
            ?.toList()
            ?: emptyList()

    fun membersInRoom(
        sessionId: SessionId,
        roomId: RoomId,
    ): List<SessionId> {
        val group = groupBySession[sessionId] ?: return listOf(sessionId)
        return group.members.filter { sid ->
            val p = players.get(sid)
            p != null && p.roomId == roomId
        }
    }

    suspend fun invite(
        inviterSid: SessionId,
        targetName: String,
    ): String? {
        val inviter = players.get(inviterSid) ?: return "You are not connected."

        val targetSid =
            players.findSessionByName(targetName)
                ?: return "Player '$targetName' is not online."
        if (targetSid == inviterSid) return "You cannot invite yourself."

        val target = players.get(targetSid) ?: return "Player '$targetName' is not online."
        if (inviter.roomId != target.roomId) return "${target.name} is not in the same room."

        // Check if target is already in a group
        val targetGroup = groupBySession[targetSid]
        if (targetGroup != null) return "${target.name} is already in a group."

        // Check inviter's group size
        val inviterGroup = groupBySession[inviterSid]
        if (inviterGroup != null) {
            if (inviterGroup.leader != inviterSid) return "Only the group leader can invite."
            if (inviterGroup.members.size >= maxGroupSize) return "Your group is full ($maxGroupSize members)."
        }

        // Clean expired invites
        cleanExpiredInvites()

        // Check for existing pending invite
        val existing = pendingInvites[targetSid]
        if (existing != null && existing.inviterSid == inviterSid) {
            return "You have already invited ${target.name}."
        }

        val now = clock.millis()
        pendingInvites[targetSid] =
            PendingInvite(
                inviterSid = inviterSid,
                inviteeSid = targetSid,
                expiresAtMs = now + inviteTimeoutMs,
            )

        outbound.send(OutboundEvent.SendText(inviterSid, "You invite ${target.name} to join your group."))
        outbound.send(
            OutboundEvent.SendText(
                targetSid,
                "${inviter.name} invites you to join their group. Type 'group accept' to accept.",
            ),
        )
        outbound.send(OutboundEvent.SendPrompt(targetSid))
        return null
    }

    suspend fun accept(inviteeSid: SessionId): String? {
        cleanExpiredInvites()

        val invite =
            pendingInvites.remove(inviteeSid)
                ?: return "You have no pending group invite."

        val inviter = players.get(invite.inviterSid)
        if (inviter == null) {
            return "The inviter is no longer online."
        }

        // Verify invitee is not already in a group
        if (groupBySession.containsKey(inviteeSid)) {
            return "You are already in a group."
        }

        val invitee = players.get(inviteeSid) ?: return "You are not connected."

        // Get or create inviter's group
        val group =
            groupBySession[invite.inviterSid]
                ?: run {
                    val newGroup =
                        Group(
                            id = UUID.randomUUID(),
                            leader = invite.inviterSid,
                            members = mutableListOf(invite.inviterSid),
                        )
                    groupBySession[invite.inviterSid] = newGroup
                    newGroup
                }

        if (group.members.size >= maxGroupSize) {
            return "The group is full ($maxGroupSize members)."
        }

        group.members.add(inviteeSid)
        groupBySession[inviteeSid] = group

        // Notify all group members
        for (sid in group.members) {
            if (sid == inviteeSid) {
                outbound.send(OutboundEvent.SendText(sid, "You join ${inviter.name}'s group."))
            } else {
                outbound.send(OutboundEvent.SendText(sid, "${invitee.name} joins the group."))
            }
        }

        return null
    }

    suspend fun leave(sessionId: SessionId): String? {
        val group =
            groupBySession[sessionId]
                ?: return "You are not in a group."

        val player = players.get(sessionId)
        val playerName = player?.name ?: "Someone"

        group.members.remove(sessionId)
        groupBySession.remove(sessionId)

        outbound.send(OutboundEvent.SendText(sessionId, "You leave the group."))

        if (group.members.size <= 1) {
            // Group dissolves
            val remaining = group.members.firstOrNull()
            if (remaining != null) {
                groupBySession.remove(remaining)
                outbound.send(OutboundEvent.SendText(remaining, "$playerName leaves the group. The group has been disbanded."))
            }
            return null
        }

        // Transfer leadership if needed
        if (group.leader == sessionId) {
            group.leader = group.members.first()
            val newLeaderName = players.get(group.leader)?.name ?: "Someone"
            for (sid in group.members) {
                outbound.send(OutboundEvent.SendText(sid, "$playerName leaves the group. $newLeaderName is now the leader."))
            }
        } else {
            for (sid in group.members) {
                outbound.send(OutboundEvent.SendText(sid, "$playerName leaves the group."))
            }
        }

        return null
    }

    suspend fun kick(
        leaderSid: SessionId,
        targetName: String,
    ): String? {
        val group =
            groupBySession[leaderSid]
                ?: return "You are not in a group."
        if (group.leader != leaderSid) return "Only the group leader can kick members."

        val targetSid =
            players.findSessionByName(targetName)
                ?: return "Player '$targetName' is not online."
        if (targetSid == leaderSid) return "You cannot kick yourself. Use 'group leave' instead."

        if (!group.members.contains(targetSid)) {
            return "${players.get(targetSid)?.name ?: targetName} is not in your group."
        }

        val target = players.get(targetSid)
        val kickedName = target?.name ?: targetName

        group.members.remove(targetSid)
        groupBySession.remove(targetSid)

        outbound.send(OutboundEvent.SendText(targetSid, "You have been kicked from the group."))

        if (group.members.size <= 1) {
            // Group dissolves
            val remaining = group.members.firstOrNull()
            if (remaining != null) {
                groupBySession.remove(remaining)
                outbound.send(
                    OutboundEvent.SendText(
                        remaining,
                        "$kickedName has been kicked. The group has been disbanded.",
                    ),
                )
            }
            return null
        }

        for (sid in group.members) {
            outbound.send(OutboundEvent.SendText(sid, "$kickedName has been kicked from the group."))
        }

        return null
    }

    suspend fun list(sessionId: SessionId): String? {
        val group = groupBySession[sessionId]
        if (group == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "You are not in a group."))
            return null
        }

        val lines = mutableListOf<String>()
        lines.add("Group members:")
        for (sid in group.members) {
            val p = players.get(sid) ?: continue
            val leaderTag = if (sid == group.leader) " (leader)" else ""
            val className =
                PlayerClass
                    .fromString(p.playerClass)
                    ?.displayName ?: p.playerClass
            lines.add("  ${p.name} — Level ${p.level} $className [${p.hp}/${p.maxHp} HP]$leaderTag")
        }

        outbound.send(OutboundEvent.SendInfo(sessionId, lines.joinToString("\n")))
        return null
    }

    suspend fun gtell(
        sessionId: SessionId,
        message: String,
    ): String? {
        val group =
            groupBySession[sessionId]
                ?: return "You are not in a group."

        val player = players.get(sessionId) ?: return "You are not connected."

        for (sid in group.members) {
            outbound.send(
                OutboundEvent.SendText(sid, "[Group] ${player.name}: $message"),
            )
        }

        return null
    }

    fun onPlayerDisconnected(sessionId: SessionId) {
        // Remove pending invites where this player is invitee
        pendingInvites.remove(sessionId)
        // Remove pending invites where this player is inviter
        pendingInvites.entries.removeAll { it.value.inviterSid == sessionId }

        val group = groupBySession.remove(sessionId) ?: return
        group.members.remove(sessionId)

        if (group.members.size <= 1) {
            // Group dissolves
            val remaining = group.members.firstOrNull()
            if (remaining != null) {
                groupBySession.remove(remaining)
            }
            return
        }

        // Transfer leadership if needed
        if (group.leader == sessionId) {
            group.leader = group.members.first()
        }
    }

    fun remapSession(
        oldSid: SessionId,
        newSid: SessionId,
    ) {
        // Remap pending invites
        pendingInvites.remove(oldSid)?.let { invite ->
            pendingInvites[newSid] = invite.copy(inviteeSid = newSid)
        }
        val inviterEntries = pendingInvites.entries.filter { it.value.inviterSid == oldSid }
        for (entry in inviterEntries) {
            pendingInvites[entry.key] = entry.value.copy(inviterSid = newSid)
        }

        val group = groupBySession.remove(oldSid) ?: return
        groupBySession[newSid] = group

        val idx = group.members.indexOf(oldSid)
        if (idx >= 0) {
            group.members[idx] = newSid
        }
        if (group.leader == oldSid) {
            group.leader = newSid
        }
    }

    private fun cleanExpiredInvites() {
        val now = clock.millis()
        pendingInvites.entries.removeAll { it.value.expiresAtMs <= now }
    }
}
