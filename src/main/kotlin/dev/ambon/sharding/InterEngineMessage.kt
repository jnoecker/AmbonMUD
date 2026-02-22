package dev.ambon.sharding

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import dev.ambon.domain.ids.RoomId

/**
 * Messages exchanged between engine instances for cross-zone operations.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(InterEngineMessage.GlobalBroadcast::class, name = "global_broadcast"),
    JsonSubTypes.Type(InterEngineMessage.TellMessage::class, name = "tell"),
    JsonSubTypes.Type(InterEngineMessage.WhoRequest::class, name = "who_request"),
    JsonSubTypes.Type(InterEngineMessage.WhoResponse::class, name = "who_response"),
    JsonSubTypes.Type(InterEngineMessage.KickRequest::class, name = "kick"),
    JsonSubTypes.Type(InterEngineMessage.ShutdownRequest::class, name = "shutdown"),
    JsonSubTypes.Type(InterEngineMessage.PlayerHandoff::class, name = "player_handoff"),
    JsonSubTypes.Type(InterEngineMessage.HandoffAck::class, name = "handoff_ack"),
    JsonSubTypes.Type(InterEngineMessage.SessionRedirect::class, name = "session_redirect"),
    JsonSubTypes.Type(InterEngineMessage.TransferRequest::class, name = "transfer_request"),
)
sealed interface InterEngineMessage {
    /** Broadcast to all players on all engines (gossip, server messages). */
    data class GlobalBroadcast(
        val broadcastType: BroadcastType,
        val senderName: String,
        val text: String,
    ) : InterEngineMessage

    /** Cross-engine private message (tell). */
    data class TellMessage(
        val fromName: String,
        val toName: String,
        val text: String,
    ) : InterEngineMessage

    /** Request: "who is online?" — each engine replies with its player list. */
    data class WhoRequest(
        val requestId: String,
        val replyToEngineId: String,
    ) : InterEngineMessage

    /** Response to WhoRequest. */
    data class WhoResponse(
        val requestId: String,
        val players: List<PlayerSummary>,
    ) : InterEngineMessage

    /** Staff: kick a player on another engine. */
    data class KickRequest(
        val targetPlayerName: String,
    ) : InterEngineMessage

    /** Broadcast shutdown to all engines. */
    data class ShutdownRequest(
        val initiatorName: String,
    ) : InterEngineMessage

    /** Player is moving from one engine's zone to another. */
    data class PlayerHandoff(
        val sessionId: Long,
        val targetRoomId: String,
        val playerState: SerializedPlayerState,
        val gatewayId: Int,
    ) : InterEngineMessage

    /** Acknowledge a player handoff. */
    data class HandoffAck(
        val sessionId: Long,
        val success: Boolean,
        val errorMessage: String? = null,
    ) : InterEngineMessage

    /** Redirect a gateway to route a session to a different engine. */
    data class SessionRedirect(
        val sessionId: Long,
        val newEngineId: String,
        val newEngineHost: String,
        val newEnginePort: Int,
    ) : InterEngineMessage

    /** Staff: transfer a player on another engine to a specific room. */
    data class TransferRequest(
        val staffName: String,
        val targetPlayerName: String,
        val targetRoomId: String,
    ) : InterEngineMessage
}

enum class BroadcastType {
    GOSSIP,
    SHUTDOWN,
    ANNOUNCEMENT,
}

/** Minimal player info for cross-engine queries (who, tell routing). */
data class PlayerSummary(
    val name: String,
    val roomId: String,
    val level: Int,
)

/**
 * Full player state for cross-zone handoff. Serialized as JSON for transport.
 */
data class SerializedPlayerState(
    val playerId: Long?,
    val name: String,
    val roomId: String,
    val hp: Int,
    val maxHp: Int,
    val baseMaxHp: Int,
    val constitution: Int,
    val level: Int,
    val xpTotal: Long,
    val ansiEnabled: Boolean,
    val isStaff: Boolean,
    val passwordHash: String,
    val createdEpochMs: Long,
    val lastSeenEpochMs: Long,
    val inventoryItemIds: List<String> = emptyList(),
    val equippedItems: Map<String, String> = emptyMap(), // slot name → item id
)
