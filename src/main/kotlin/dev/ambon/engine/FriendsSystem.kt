package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.events.OutboundEvent
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

class FriendsSystem(
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val gmcpEmitter: GmcpEmitter? = null,
    private val maxFriends: Int = 50,
    private val markPlayerDirty: suspend (SessionId) -> Unit = {},
) {
    /**
     * Adds [targetName] to the player's friends list.
     * Returns an error message or null on success.
     */
    suspend fun addFriend(sessionId: SessionId, targetName: String): String? {
        val ps = players.get(sessionId) ?: return "You are not connected."
        val normalizedTarget = targetName.trim()
        if (normalizedTarget.isEmpty()) return "friend add <player>"

        if (normalizedTarget.equals(ps.name, ignoreCase = true)) {
            return "You cannot add yourself as a friend."
        }

        val key = normalizedTarget.lowercase()
        if (ps.friendsList.contains(key)) {
            return "$normalizedTarget is already on your friends list."
        }

        if (ps.friendsList.size >= maxFriends) {
            return "Your friends list is full ($maxFriends maximum)."
        }

        // Verify the player name exists (online or offline)
        val onlineTarget = players.getByName(normalizedTarget)
        if (onlineTarget == null && !players.hasRegisteredName(normalizedTarget)) {
            return "No player named '$normalizedTarget' exists."
        }

        val displayName = onlineTarget?.name ?: normalizedTarget
        ps.friendsList.add(key)
        markPlayerDirty(sessionId)

        outbound.send(OutboundEvent.SendInfo(sessionId, "$displayName has been added to your friends list."))
        gmcpEmitter?.sendFriendsList(sessionId, buildFriendsInfo(ps))
        return null
    }

    /**
     * Removes [targetName] from the player's friends list.
     * Returns an error message or null on success.
     */
    suspend fun removeFriend(sessionId: SessionId, targetName: String): String? {
        val ps = players.get(sessionId) ?: return "You are not connected."
        val key = targetName.trim().lowercase()
        if (key.isEmpty()) return "friend remove <player>"

        if (!ps.friendsList.remove(key)) {
            return "$targetName is not on your friends list."
        }

        markPlayerDirty(sessionId)

        outbound.send(OutboundEvent.SendInfo(sessionId, "$targetName has been removed from your friends list."))
        gmcpEmitter?.sendFriendsList(sessionId, buildFriendsInfo(ps))
        return null
    }

    /**
     * Lists all friends with online status and zone.
     * Returns an error message or null on success.
     */
    suspend fun listFriends(sessionId: SessionId): String? {
        val ps = players.get(sessionId) ?: return "You are not connected."

        if (ps.friendsList.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "Your friends list is empty."))
            return null
        }

        val lines = buildString {
            appendLine("=== Friends List (${ps.friendsList.size}/$maxFriends) ===")
            for (friendKey in ps.friendsList.sorted()) {
                val online = players.getByName(friendKey)
                if (online != null) {
                    val zone = online.roomId.zone
                    append("  ${online.name} - Online ($zone)")
                } else {
                    append("  $friendKey - Offline")
                }
                appendLine()
            }
        }.trimEnd()

        outbound.send(OutboundEvent.SendInfo(sessionId, lines))
        gmcpEmitter?.sendFriendsList(sessionId, buildFriendsInfo(ps))
        return null
    }

    /**
     * Called when a player logs in. Notifies all online players who have this
     * player on their friends list.
     */
    suspend fun onPlayerLogin(sessionId: SessionId) {
        val ps = players.get(sessionId) ?: return
        val lowerName = ps.name.lowercase()

        for (other in players.allPlayers()) {
            if (other.sessionId == sessionId) continue
            if (other.friendsList.contains(lowerName)) {
                outbound.send(
                    OutboundEvent.SendInfo(other.sessionId, "Your friend ${ps.name} has logged in."),
                )
                gmcpEmitter?.sendFriendOnline(other.sessionId, ps.name, ps.level)
            }
        }
    }

    /**
     * Called when a player logs out. Notifies all online players who have this
     * player on their friends list.
     */
    suspend fun onPlayerLogout(playerName: String) {
        val lowerName = playerName.lowercase()

        for (other in players.allPlayers()) {
            if (other.name.equals(playerName, ignoreCase = true)) continue
            if (other.friendsList.contains(lowerName)) {
                outbound.send(
                    OutboundEvent.SendInfo(other.sessionId, "Your friend $playerName has logged out."),
                )
                gmcpEmitter?.sendFriendOffline(other.sessionId, playerName)
            }
        }
    }

    private fun buildFriendsInfo(ps: PlayerState): List<FriendInfo> =
        ps.friendsList.sorted().map { friendKey ->
            val online = players.getByName(friendKey)
            FriendInfo(
                name = online?.name ?: friendKey,
                online = online != null,
                level = online?.level,
                zone = online?.roomId?.zone,
            )
        }
}

data class FriendInfo(
    val name: String,
    val online: Boolean,
    val level: Int?,
    val zone: String?,
)
