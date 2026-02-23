package dev.ambon.sharding

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.domain.items.ItemUseEffect
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.PlayerId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock

private val log = KotlinLogging.logger {}

sealed interface HandoffResult {
    data class Initiated(
        val targetEngine: EngineAddress,
    ) : HandoffResult

    data object PlayerNotFound : HandoffResult

    data object NoEngineForZone : HandoffResult

    data object AlreadyInTransit : HandoffResult
}

sealed interface HandoffAckResult {
    data class Completed(
        val targetEngine: EngineAddress,
    ) : HandoffAckResult

    data class Failed(
        val errorMessage: String?,
    ) : HandoffAckResult

    data object NotPending : HandoffAckResult
}

data class TimedOutHandoff(
    val sessionId: SessionId,
    val targetRoomId: RoomId,
    val targetEngine: EngineAddress,
)

/**
 * Manages cross-zone player handoffs between engine instances.
 *
 * Source side:
 * - [initiateHandoff] sends a handoff message and marks the session in transit.
 * - [handleAck] finalizes the transfer only after target ack.
 * - [expireTimedOut] surfaces stale in-transit handoffs for rollback messaging.
 *
 * Target side:
 * - [acceptHandoff] restores player state and sends an ack back to source.
 */
class HandoffManager(
    private val engineId: String,
    private val players: PlayerRegistry,
    private val items: ItemRegistry,
    private val outbound: OutboundBus,
    private val bus: InterEngineBus,
    private val zoneRegistry: ZoneRegistry,
    private val isTargetRoomLocal: (RoomId) -> Boolean = { true },
    private val clock: Clock = Clock.systemUTC(),
    private val ackTimeoutMs: Long = 2_000L,
) {
    init {
        require(ackTimeoutMs > 0L) { "ackTimeoutMs must be > 0" }
    }

    private data class PendingHandoff(
        val playerName: String,
        val fromRoomId: RoomId,
        val targetRoomId: RoomId,
        val targetEngine: EngineAddress,
        val deadlineEpochMs: Long,
    )

    private val pendingBySession = mutableMapOf<SessionId, PendingHandoff>()

    fun isInTransit(sessionId: SessionId): Boolean = pendingBySession.containsKey(sessionId)

    fun cancelIfPending(sessionId: SessionId) {
        pendingBySession.remove(sessionId)
    }

    suspend fun initiateHandoff(
        sessionId: SessionId,
        targetRoomId: RoomId,
    ): HandoffResult {
        if (pendingBySession.containsKey(sessionId)) {
            return HandoffResult.AlreadyInTransit
        }

        val player = players.get(sessionId) ?: return HandoffResult.PlayerNotFound
        val targetEngine = zoneRegistry.ownerOf(targetRoomId.zone) ?: return HandoffResult.NoEngineForZone

        val serialized =
            serializePlayer(
                player = player,
                targetRoomId = targetRoomId,
                inventory = items.inventory(sessionId),
                equipment = items.equipment(sessionId),
            )
        val handoff =
            InterEngineMessage.PlayerHandoff(
                sessionId = sessionId.value,
                targetRoomId = targetRoomId.value,
                playerState = serialized,
                gatewayId = 0,
                sourceEngineId = engineId,
            )

        bus.sendTo(targetEngine.engineId, handoff)
        pendingBySession[sessionId] =
            PendingHandoff(
                playerName = player.name,
                fromRoomId = player.roomId,
                targetRoomId = targetRoomId,
                targetEngine = targetEngine,
                deadlineEpochMs = clock.millis() + ackTimeoutMs,
            )

        outbound.send(OutboundEvent.SendText(sessionId, "The air shimmers as you cross into new territory..."))
        log.info {
            "Handoff initiated: player=${player.name} session=${sessionId.value} " +
                "from=$engineId to=${targetEngine.engineId} targetRoom=${targetRoomId.value}"
        }
        return HandoffResult.Initiated(targetEngine)
    }

    suspend fun handleAck(msg: InterEngineMessage.HandoffAck): HandoffAckResult {
        val sessionId = SessionId(msg.sessionId)
        val pending = pendingBySession.remove(sessionId) ?: return HandoffAckResult.NotPending

        if (!msg.success) {
            return HandoffAckResult.Failed(msg.errorMessage)
        }

        val player = players.get(sessionId)
        if (player == null) {
            log.warn { "Received successful handoff ack for missing session=${sessionId.value}" }
            return HandoffAckResult.Completed(pending.targetEngine)
        }

        for (other in players.playersInRoom(pending.fromRoomId)) {
            if (other.sessionId != sessionId) {
                outbound.send(OutboundEvent.SendText(other.sessionId, "${pending.playerName} leaves."))
            }
        }

        player.roomId = pending.targetRoomId
        players.removeForHandoff(sessionId)
        outbound.send(
            OutboundEvent.SessionRedirect(
                sessionId = sessionId,
                newEngineId = pending.targetEngine.engineId,
                newEngineHost = pending.targetEngine.host,
                newEnginePort = pending.targetEngine.port,
            ),
        )

        return HandoffAckResult.Completed(pending.targetEngine)
    }

    fun expireTimedOut(nowEpochMs: Long = clock.millis()): List<TimedOutHandoff> {
        if (pendingBySession.isEmpty()) return emptyList()

        val timedOut = mutableListOf<TimedOutHandoff>()
        val itr = pendingBySession.iterator()
        while (itr.hasNext()) {
            val (sessionId, pending) = itr.next()
            if (nowEpochMs < pending.deadlineEpochMs) continue
            timedOut +=
                TimedOutHandoff(
                    sessionId = sessionId,
                    targetRoomId = pending.targetRoomId,
                    targetEngine = pending.targetEngine,
                )
            itr.remove()
        }
        return timedOut
    }

    suspend fun acceptHandoff(handoff: InterEngineMessage.PlayerHandoff): SessionId? {
        val sessionId = SessionId(handoff.sessionId)
        val state = handoff.playerState
        val targetRoom = RoomId(handoff.targetRoomId)

        if (!isTargetRoomLocal(targetRoom)) {
            log.warn { "Rejecting handoff for session=${sessionId.value}: target room ${targetRoom.value} is not local" }
            sendAck(
                targetEngineId = handoff.sourceEngineId,
                sessionId = sessionId,
                success = false,
                errorMessage = "Target room is not hosted on this engine",
            )
            return null
        }

        if (players.get(sessionId) != null) {
            log.warn { "Duplicate handoff for session=${sessionId.value}, player=${state.name}" }
            sendAck(
                targetEngineId = handoff.sourceEngineId,
                sessionId = sessionId,
                success = false,
                errorMessage = "Session already exists on target engine",
            )
            return null
        }

        val ps =
            PlayerState(
                sessionId = sessionId,
                name = state.name,
                roomId = targetRoom,
                playerId = state.playerId?.let { PlayerId(it) },
                baseMaxHp = state.baseMaxHp,
                hp = state.hp,
                maxHp = state.maxHp,
                constitution = state.constitution,
                level = state.level,
                xpTotal = state.xpTotal,
                ansiEnabled = state.ansiEnabled,
                isStaff = state.isStaff,
                mana = state.mana,
                maxMana = state.maxMana,
                baseMana = state.baseMana,
                playerClass =
                    dev.ambon.domain.character.PlayerClass
                        .fromString(state.playerClass)
                        ?: dev.ambon.domain.character.PlayerClass.WARRIOR,
                playerRace =
                    dev.ambon.domain.character.PlayerRace
                        .fromString(state.playerRace)
                        ?: dev.ambon.domain.character.PlayerRace.HUMAN,
            )

        try {
            players.bindFromHandoff(sessionId, ps)

            for (item in state.inventoryItems) {
                items.addToInventory(sessionId, deserializeItem(item))
            }
            for ((slotName, item) in state.equippedItems) {
                val slot = ItemSlot.parse(slotName) ?: continue
                items.setEquippedItem(sessionId, slot, deserializeItem(item))
            }

            for (other in players.playersInRoom(targetRoom)) {
                if (other.sessionId != sessionId) {
                    outbound.send(OutboundEvent.SendText(other.sessionId, "${state.name} enters."))
                }
            }
        } catch (err: Throwable) {
            log.warn(err) { "Failed to accept handoff for session=${sessionId.value}" }
            runCatching { players.removeForHandoff(sessionId) }
            sendAck(
                targetEngineId = handoff.sourceEngineId,
                sessionId = sessionId,
                success = false,
                errorMessage = "Failed to restore player on target engine",
            )
            return null
        }

        log.info {
            "Handoff accepted: player=${state.name} session=${sessionId.value} " +
                "room=${targetRoom.value} on engine=$engineId"
        }
        sendAck(
            targetEngineId = handoff.sourceEngineId,
            sessionId = sessionId,
            success = true,
            errorMessage = null,
        )
        return sessionId
    }

    private suspend fun sendAck(
        targetEngineId: String,
        sessionId: SessionId,
        success: Boolean,
        errorMessage: String?,
    ) {
        if (targetEngineId.isBlank()) return
        bus.sendTo(
            targetEngineId,
            InterEngineMessage.HandoffAck(
                sessionId = sessionId.value,
                success = success,
                errorMessage = errorMessage,
            ),
        )
    }

    companion object {
        fun serializePlayer(
            player: PlayerState,
            targetRoomId: RoomId,
            inventory: List<ItemInstance>,
            equipment: Map<ItemSlot, ItemInstance>,
        ): SerializedPlayerState =
            SerializedPlayerState(
                playerId = player.playerId?.value,
                name = player.name,
                roomId = targetRoomId.value,
                hp = player.hp,
                maxHp = player.maxHp,
                baseMaxHp = player.baseMaxHp,
                constitution = player.constitution,
                level = player.level,
                xpTotal = player.xpTotal,
                ansiEnabled = player.ansiEnabled,
                isStaff = player.isStaff,
                passwordHash = "",
                createdEpochMs = 0L,
                lastSeenEpochMs = 0L,
                mana = player.mana,
                maxMana = player.maxMana,
                baseMana = player.baseMana,
                inventoryItems = inventory.map { serializeItem(it) },
                equippedItems =
                    equipment
                        .mapKeys { (slot, _) -> slot.name }
                        .mapValues { (_, instance) -> serializeItem(instance) },
                playerClass = player.playerClass.name,
                playerRace = player.playerRace.name,
            )

        fun serializeItem(instance: ItemInstance): SerializedItem =
            SerializedItem(
                id = instance.id.value,
                keyword = instance.item.keyword,
                displayName = instance.item.displayName,
                description = instance.item.description,
                slot = instance.item.slot?.name,
                damage = instance.item.damage,
                armor = instance.item.armor,
                constitution = instance.item.constitution,
                consumable = instance.item.consumable,
                charges = instance.item.charges,
                onUse =
                    instance.item.onUse?.let { effect ->
                        SerializedItemUseEffect(
                            healHp = effect.healHp,
                            grantXp = effect.grantXp,
                        )
                    },
                matchByKey = instance.item.matchByKey,
            )

        fun deserializeItem(serialized: SerializedItem): ItemInstance =
            ItemInstance(
                id = ItemId(serialized.id),
                item =
                    Item(
                        keyword = serialized.keyword,
                        displayName = serialized.displayName,
                        description = serialized.description,
                        slot = serialized.slot?.let { ItemSlot.parse(it) },
                        damage = serialized.damage,
                        armor = serialized.armor,
                        constitution = serialized.constitution,
                        consumable = serialized.consumable,
                        charges = serialized.charges,
                        onUse =
                            serialized.onUse?.let { effect ->
                                ItemUseEffect(
                                    healHp = effect.healHp,
                                    grantXp = effect.grantXp,
                                )
                            },
                        matchByKey = serialized.matchByKey,
                    ),
            )
    }
}
