package dev.ambon.sharding

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.PlayerId
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

sealed interface HandoffResult {
    data class Initiated(val targetEngine: EngineAddress) : HandoffResult
    data object PlayerNotFound : HandoffResult
    data object NoEngineForZone : HandoffResult
}

/**
 * Manages cross-zone player handoffs between engine instances.
 *
 * **Source side** ([initiateHandoff]): serializes the player's full state
 * (stats, inventory, equipment), sends a [InterEngineMessage.PlayerHandoff]
 * to the target engine, and removes the player from local registries.
 *
 * **Target side** ([acceptHandoff]): deserializes the incoming state,
 * binds the player into local registries, and restores inventory/equipment.
 */
class HandoffManager(
    private val engineId: String,
    private val players: PlayerRegistry,
    private val items: ItemRegistry,
    private val outbound: OutboundBus,
    private val bus: InterEngineBus,
    private val zoneRegistry: ZoneRegistry,
    private val clock: java.time.Clock = java.time.Clock.systemUTC(),
) {
    /**
     * Initiate a cross-zone handoff for a player moving to [targetRoomId].
     *
     * 1. Serializes the player's full state + inventory + equipment
     * 2. Persists the player with the target room (via removeForHandoff)
     * 3. Sends the handoff message to the target engine
     * 4. Removes the player from local registries
     * 5. Notifies the room of departure
     */
    suspend fun initiateHandoff(
        sessionId: SessionId,
        targetRoomId: RoomId,
    ): HandoffResult {
        val player = players.get(sessionId) ?: return HandoffResult.PlayerNotFound

        val targetEngine = zoneRegistry.ownerOf(targetRoomId.zone)
            ?: return HandoffResult.NoEngineForZone

        // Serialize player + items before removing
        val inv = items.inventory(sessionId)
        val eq = items.equipment(sessionId)
        val serialized = serializePlayer(player, targetRoomId, inv, eq)

        val handoff = InterEngineMessage.PlayerHandoff(
            sessionId = sessionId.value,
            targetRoomId = targetRoomId.value,
            playerState = serialized,
            gatewayId = 0,
            sourceEngineId = engineId,
        )

        // Notify the player
        outbound.send(OutboundEvent.SendText(sessionId, "The air shimmers as you cross into new territory..."))

        // Broadcast departure to the room
        for (other in players.playersInRoom(player.roomId)) {
            if (other.sessionId != sessionId) {
                outbound.send(OutboundEvent.SendText(other.sessionId, "${player.name} leaves."))
            }
        }

        // Remove from local registries (this also persists with current room)
        // Update room to target before removing so persistence saves the correct room
        player.roomId = targetRoomId
        players.removeForHandoff(sessionId)

        // Send to target engine
        bus.sendTo(targetEngine.engineId, handoff)

        log.info {
            "Handoff initiated: player=${player.name} session=${sessionId.value} " +
                "from=$engineId to=${targetEngine.engineId} targetRoom=${targetRoomId.value}"
        }

        return HandoffResult.Initiated(targetEngine)
    }

    /**
     * Accept an incoming player handoff from another engine.
     *
     * Reconstructs the player state and inventory/equipment in local registries.
     * Returns the SessionId if successful, null on failure.
     */
    suspend fun acceptHandoff(handoff: InterEngineMessage.PlayerHandoff): SessionId? {
        val state = handoff.playerState
        val sessionId = SessionId(handoff.sessionId)
        val targetRoom = RoomId(handoff.targetRoomId)

        // Check for duplicate — player might already be here from a retry
        if (players.get(sessionId) != null) {
            log.warn { "Duplicate handoff for session=${sessionId.value}, player=${state.name} — ignoring" }
            return null
        }

        val ps = PlayerState(
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
        )

        // Bind player to local registries
        players.bindFromHandoff(sessionId, ps)

        // Restore inventory
        for (item in state.inventoryItems) {
            items.addToInventory(sessionId, deserializeItem(item))
        }

        // Restore equipment
        for ((slotName, item) in state.equippedItems) {
            val slot = ItemSlot.parse(slotName) ?: continue
            items.setEquippedItem(sessionId, slot, deserializeItem(item))
        }

        // Broadcast arrival to the room
        for (other in players.playersInRoom(targetRoom)) {
            if (other.sessionId != sessionId) {
                outbound.send(OutboundEvent.SendText(other.sessionId, "${state.name} enters."))
            }
        }

        log.info {
            "Handoff accepted: player=${state.name} session=${sessionId.value} " +
                "room=${targetRoom.value} on engine=$engineId"
        }

        // Send ack back to source engine (best-effort)
        if (handoff.sourceEngineId.isNotEmpty()) {
            bus.sendTo(
                handoff.sourceEngineId,
                InterEngineMessage.HandoffAck(
                    sessionId = sessionId.value,
                    success = true,
                ),
            )
        }

        return sessionId
    }

    companion object {
        fun serializePlayer(
            player: PlayerState,
            targetRoomId: RoomId,
            inventory: List<ItemInstance>,
            equipment: Map<ItemSlot, ItemInstance>,
        ): SerializedPlayerState = SerializedPlayerState(
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
            inventoryItems = inventory.map { serializeItem(it) },
            equippedItems = equipment.mapKeys { (slot, _) -> slot.name }
                .mapValues { (_, instance) -> serializeItem(instance) },
        )

        fun serializeItem(instance: ItemInstance): SerializedItem = SerializedItem(
            id = instance.id.value,
            keyword = instance.item.keyword,
            displayName = instance.item.displayName,
            description = instance.item.description,
            slot = instance.item.slot?.name,
            damage = instance.item.damage,
            armor = instance.item.armor,
            constitution = instance.item.constitution,
            matchByKey = instance.item.matchByKey,
        )

        fun deserializeItem(serialized: SerializedItem): ItemInstance = ItemInstance(
            id = ItemId(serialized.id),
            item = Item(
                keyword = serialized.keyword,
                displayName = serialized.displayName,
                description = serialized.description,
                slot = serialized.slot?.let { ItemSlot.parse(it) },
                damage = serialized.damage,
                armor = serialized.armor,
                constitution = serialized.constitution,
                matchByKey = serialized.matchByKey,
            ),
        )
    }
}
