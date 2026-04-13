package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.config.HousingConfig
import dev.ambon.config.RoomTemplateConfig
import dev.ambon.domain.housing.HouseRecord
import dev.ambon.domain.housing.HouseRoomRecord
import dev.ambon.domain.housing.RoomTemplate
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.Room
import dev.ambon.domain.world.World
import dev.ambon.domain.world.opposite
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.HouseRepository
import dev.ambon.persistence.PlayerId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock

private val log = KotlinLogging.logger {}

/** Sentinel room-id local part used for the dynamic exit back to the world. */
const val HOUSE_EXIT_LOCAL = "exit"

/** Fallback templates used when housing is enabled but no templates are configured. */
private val DEFAULT_TEMPLATES: Map<String, RoomTemplate> = mapOf(
    "default_entry" to RoomTemplate(
        id = "default_entry",
        title = "A Threadbare Player House",
        description = "A modest dwelling with bare walls and a creaky floor. " +
            "It isn't much, but it's yours. A few nails in the wall suggest " +
            "where decorations might hang someday.",
        cost = 100L,
        isEntry = true,
        safe = true,
    ),
)

/** Sealed result for [HousingSystem.enterOwnHouse]. */
sealed interface HouseEntryResult {
    data class Success(
        val entryRoomId: RoomId,
    ) : HouseEntryResult

    data class Error(
        val message: String,
    ) : HouseEntryResult
}

class HousingSystem(
    private val players: PlayerRegistry,
    private val houseRepo: HouseRepository,
    private val world: World,
    private val outbound: OutboundBus,
    private val items: ItemRegistry,
    private val config: HousingConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val markPlayerDirty: suspend (SessionId) -> Unit = {},
    imagesBaseUrl: String = "/images/",
) : GameSystem {
    private val imagesBase = if (imagesBaseUrl.endsWith("/")) imagesBaseUrl else "$imagesBaseUrl/"

    /** Templates keyed by id, built from config at construction time. Falls back to a default entry template. */
    val templates: Map<String, RoomTemplate> = config.templates.map { (id, cfg) ->
        id to cfg.toRoomTemplate(id)
    }.toMap().ifEmpty { DEFAULT_TEMPLATES }

    /** The entry template id (exactly one must be marked isEntry). */
    val entryTemplateId: String = templates.values.firstOrNull { it.isEntry }?.id
        ?: run {
            log.error { "No entry template (isEntry=true) found in housing config — housing will not function" }
            ""
        }

    /** In-memory cache of loaded houses. Populated on player login, stays until restart. */
    private val housesByOwner = mutableMapOf<PlayerId, HouseRecord>()

    /**
     * Tracks where each visitor (including the owner) entered from.
     * Used to resolve the dynamic "exit" when leaving the house.
     */
    private val visitorOrigins = mutableMapOf<SessionId, RoomId>()

    /** Tracks which house each session is visiting (by owner PlayerId). */
    private val sessionInHouse = mutableMapOf<SessionId, PlayerId>()

    // -------- lifecycle --------

    /**
     * Called on player login. Loads the house from persistence (if any),
     * materialises rooms in the World, sets the `hasHouse` flag on PlayerState,
     * and relocates players stranded in someone else's house after a disconnect.
     */
    suspend fun onPlayerLogin(sessionId: SessionId) {
        val ps = players.get(sessionId) ?: return
        val pid = ps.playerId ?: return

        val house = houseRepo.findByOwnerId(pid) ?: run {
            ps.hasHouse = false
            relocateIfStrandedVisitor(ps)
            return
        }
        ps.hasHouse = true
        housesByOwner[pid] = house
        materializeHouse(house)
        loadVaultItems(pid)
        log.info { "Loaded house for ${ps.name} (${house.rooms.size} room(s))" }

        // If they reconnected in their own house, restore visitor tracking
        if (isHouseRoom(ps.roomId) && houseOwnerName(ps.roomId) == ps.name) {
            val recallInn = ps.recallRoomId ?: world.startRoom
            visitorOrigins[sessionId] = recallInn
            sessionInHouse[sessionId] = pid
        } else {
            relocateIfStrandedVisitor(ps)
        }
    }

    /**
     * If a player reconnects in someone else's house (invite was session-only),
     * move them to their recall room.
     */
    private fun relocateIfStrandedVisitor(ps: PlayerState) {
        if (isHouseRoom(ps.roomId) && houseOwnerName(ps.roomId) != ps.name) {
            val dest = ps.recallRoomId ?: world.startRoom
            ps.roomId = dest
            log.info { "${ps.name} was stranded in a house after reconnect; relocated to $dest" }
        }
    }

    override fun remapSession(oldSid: SessionId, newSid: SessionId) {
        visitorOrigins.remapKey(oldSid, newSid)
        sessionInHouse.remapKey(oldSid, newSid)
    }

    override suspend fun onPlayerDisconnected(sessionId: SessionId) {
        val ps = players.get(sessionId) ?: return
        val pid = ps.playerId ?: return

        // If owner disconnects, save vault items then boot all visitors
        if (housesByOwner.containsKey(pid)) {
            saveVaultItems(pid)
            bootAllVisitors(pid, "The warmth fades as the owner departs. You find yourself back where you came from.")
        }

        // Clean up this session's visitor state (if they were visiting someone else)
        visitorOrigins.remove(sessionId)
        sessionInHouse.remove(sessionId)
    }

    // -------- house creation --------

    /**
     * Creates a new house for [sessionId]. Returns an error message on failure, null on success.
     */
    suspend fun createHouse(sessionId: SessionId): String? {
        val ps = players.get(sessionId) ?: return "You are not connected."
        val pid = ps.playerId ?: return "You are not connected."

        if (ps.hasHouse) return "You already own a house."

        val entryTemplate = templates[entryTemplateId] ?: return "Housing is not configured."
        if (ps.gold < entryTemplate.cost) {
            return "You need ${entryTemplate.cost} gold to purchase a house. You have ${ps.gold}."
        }

        ps.gold -= entryTemplate.cost
        markPlayerDirty(sessionId)

        val entryRoom = HouseRoomRecord(templateId = entryTemplateId)
        val house = HouseRecord(
            ownerId = pid,
            ownerName = ps.name,
            rooms = listOf(entryRoom),
            createdAtEpochMs = clock.millis(),
        )
        houseRepo.save(house)
        housesByOwner[pid] = house
        ps.hasHouse = true
        materializeHouse(house)

        log.info { "${ps.name} purchased a house" }
        return null
    }

    // -------- room expansion --------

    /**
     * Expands the house by adding a room from [templateId] connected to the current room via [direction].
     * Returns an error message on failure, null on success.
     */
    suspend fun expandHouse(
        sessionId: SessionId,
        templateId: String,
        direction: Direction,
    ): String? {
        val ps = players.get(sessionId) ?: return "You are not connected."
        val pid = ps.playerId ?: return "You are not connected."

        if (!ps.hasHouse) return "You don't own a house."
        val house = housesByOwner[pid] ?: return "House data not loaded."

        if (!isInOwnHouse(sessionId)) return "You must be inside your house to expand it."

        val template = templates[templateId] ?: return "Unknown room template '$templateId'."
        if (template.isEntry) return "You cannot add another entry room."
        if (house.rooms.any { it.templateId == templateId }) {
            return "You already have a ${template.title}."
        }
        if (ps.gold < template.cost) {
            return "You need ${template.cost} gold. You have ${ps.gold}."
        }

        // Find current room index
        val currentRoomIndex = currentHouseRoomIndex(ps, house) ?: return "You are not in a house room."
        val currentRoom = house.rooms[currentRoomIndex]

        // Check direction is available
        if (currentRoom.exits.containsKey(direction)) return "There is already an exit ${direction.name.lowercase()}."
        if (direction == config.entryExitDirection && currentRoomIndex == 0) {
            return "That direction is reserved for the house exit."
        }

        ps.gold -= template.cost
        markPlayerDirty(sessionId)

        // Build updated rooms list
        val newRoomIndex = house.rooms.size
        val reverseDir = direction.opposite()

        val updatedCurrentRoom = currentRoom.copy(
            exits = currentRoom.exits + (direction to newRoomIndex),
        )
        val newRoom = HouseRoomRecord(
            templateId = templateId,
            exits = mapOf(reverseDir to currentRoomIndex),
        )

        val updatedRooms = house.rooms.toMutableList()
        updatedRooms[currentRoomIndex] = updatedCurrentRoom
        updatedRooms.add(newRoom)

        val updatedHouse = house.copy(rooms = updatedRooms)
        houseRepo.save(updatedHouse)
        housesByOwner[pid] = updatedHouse
        materializeHouse(updatedHouse)

        log.info { "${ps.name} expanded their house with ${template.title}" }
        return null
    }

    // -------- room customization --------

    suspend fun setRoomTitle(sessionId: SessionId, title: String): String? {
        val ps = players.get(sessionId) ?: return "You are not connected."
        val pid = ps.playerId ?: return "You are not connected."
        if (!isInOwnHouse(sessionId)) return "You must be in your own house."

        val house = housesByOwner[pid] ?: return "House data not loaded."
        val idx = currentHouseRoomIndex(ps, house) ?: return "You are not in a house room."

        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed.length > 60) return "Title must be 1–60 characters."

        val updated = updateRoom(house, idx) { it.copy(customTitle = trimmed) }
        houseRepo.save(updated)
        housesByOwner[pid] = updated
        materializeHouse(updated)

        return null
    }

    suspend fun setRoomDescription(sessionId: SessionId, description: String): String? {
        val ps = players.get(sessionId) ?: return "You are not connected."
        val pid = ps.playerId ?: return "You are not connected."
        if (!isInOwnHouse(sessionId)) return "You must be in your own house."

        val house = housesByOwner[pid] ?: return "House data not loaded."
        val idx = currentHouseRoomIndex(ps, house) ?: return "You are not in a house room."

        val trimmed = description.trim()
        if (trimmed.isEmpty() || trimmed.length > 500) return "Description must be 1–500 characters."

        val updated = updateRoom(house, idx) { it.copy(customDescription = trimmed) }
        houseRepo.save(updated)
        housesByOwner[pid] = updated
        materializeHouse(updated)

        return null
    }

    // -------- entry & exit --------

    /**
     * Enters the player into their own house from [originRoomId].
     */
    fun enterOwnHouse(sessionId: SessionId, originRoomId: RoomId): HouseEntryResult {
        val ps = players.get(sessionId) ?: return HouseEntryResult.Error("You are not connected.")
        val pid = ps.playerId ?: return HouseEntryResult.Error("You are not connected.")
        if (!ps.hasHouse) return HouseEntryResult.Error("You don't own a house.")

        val house = housesByOwner[pid] ?: return HouseEntryResult.Error("House data not loaded.")
        val entryRoomId = houseRoomId(house.ownerName, 0)

        visitorOrigins[sessionId] = originRoomId
        sessionInHouse[sessionId] = pid
        return HouseEntryResult.Success(entryRoomId)
    }

    /**
     * Resolves the destination for a player moving through the house exit.
     * Returns the origin [RoomId] or null if not applicable.
     */
    fun resolveHouseExit(sessionId: SessionId): RoomId? {
        val origin = visitorOrigins.remove(sessionId)
        sessionInHouse.remove(sessionId)
        return origin
    }

    /** Checks whether [roomId] is a housing exit sentinel. */
    fun isHouseExit(roomId: RoomId): Boolean = roomId.local == HOUSE_EXIT_LOCAL

    /** Checks whether [roomId] belongs to any player's house zone. */
    fun isHouseRoom(roomId: RoomId): Boolean = roomId.zone.startsWith("house_")

    /** Extracts the owner name from a house room id (zone is "house_<name>"). */
    fun houseOwnerName(roomId: RoomId): String? {
        val zone = roomId.zone
        return if (zone.startsWith("house_")) zone.removePrefix("house_") else null
    }

    /** Returns the HouseRecord for a given owner, if loaded. */
    fun getHouse(ownerId: PlayerId): HouseRecord? = housesByOwner[ownerId]

    /** Returns the HouseRecord by owner name, if loaded. */
    fun getHouseByOwnerName(name: String): HouseRecord? =
        housesByOwner.values.firstOrNull { it.ownerName.equals(name, ignoreCase = true) }

    /** Returns true if [sessionId] is currently in their own house. */
    fun isInOwnHouse(sessionId: SessionId): Boolean {
        val ps = players.get(sessionId) ?: return false
        val pid = ps.playerId ?: return false
        return sessionInHouse[sessionId] == pid
    }

    /** Returns true if [sessionId] is in any house (own or visiting). */
    fun isInAnyHouse(sessionId: SessionId): Boolean = sessionInHouse.containsKey(sessionId)

    /** Returns the ownerId of the house the session is currently in. */
    fun currentHouseOwner(sessionId: SessionId): PlayerId? = sessionInHouse[sessionId]

    // -------- visitor management --------

    /**
     * Invites [targetName] to the owner's house.
     * Returns an error message on failure, null on success.
     */
    suspend fun invitePlayer(sessionId: SessionId, targetName: String): String? {
        val ps = players.get(sessionId) ?: return "You are not connected."
        val pid = ps.playerId ?: return "You are not connected."
        if (!isInOwnHouse(sessionId)) return "You must be in your own house to invite someone."

        val house = housesByOwner[pid] ?: return "House data not loaded."
        val target = players.getByName(targetName) ?: return "Player '$targetName' is not online."
        if (target.sessionId == sessionId) return "You can't invite yourself."

        val targetRoom = target.roomId

        visitorOrigins[target.sessionId] = targetRoom
        sessionInHouse[target.sessionId] = pid

        return null // Caller handles moving the player and sending messages
    }

    /** Returns the entry RoomId for [ownerName]'s house. */
    fun entryRoomId(ownerName: String): RoomId = houseRoomId(ownerName, 0)

    /**
     * Boots a visitor from the owner's house. Returns an error message, or null on success.
     */
    suspend fun kickVisitor(sessionId: SessionId, targetName: String): String? {
        val ps = players.get(sessionId) ?: return "You are not connected."
        val pid = ps.playerId ?: return "You are not connected."
        if (!isInOwnHouse(sessionId)) return "You must be in your own house."

        val target = players.getByName(targetName) ?: return "Player '$targetName' is not online."
        if (target.sessionId == sessionId) return "You can't kick yourself."
        if (sessionInHouse[target.sessionId] != pid) return "'$targetName' is not in your house."

        bootVisitor(target.sessionId, "You have been asked to leave.")
        return null
    }

    /** Returns a list of visitor names in [sessionId]'s house (excluding the owner). */
    fun guestList(sessionId: SessionId): List<String> {
        val ps = players.get(sessionId) ?: return emptyList()
        val pid = ps.playerId ?: return emptyList()

        return sessionInHouse.entries
            .filter { it.value == pid && it.key != sessionId }
            .mapNotNull { players.get(it.key)?.name }
    }

    /** Returns the RoomTemplate for the room the player is currently in, if it's a house room. */
    fun currentRoomTemplate(sessionId: SessionId): RoomTemplate? {
        val ps = players.get(sessionId) ?: return null
        val pid = currentHouseOwner(sessionId) ?: return null
        val house = housesByOwner[pid] ?: return null
        val idx = currentHouseRoomIndex(ps, house) ?: return null
        return templates[house.rooms[idx].templateId]
    }

    // -------- vault items --------

    /**
     * Returns the max items allowed in the current room (vault capacity).
     * Returns 0 for non-vault rooms (no persistent storage).
     */
    fun vaultCapacity(sessionId: SessionId): Int {
        val template = currentRoomTemplate(sessionId) ?: return 0
        return template.maxDroppedItems
    }

    /**
     * Persists all room items from vault rooms into the house record.
     * Called on owner disconnect and during the save/flush cycle.
     */
    suspend fun saveVaultItems(ownerId: PlayerId) {
        val house = housesByOwner[ownerId] ?: return
        var changed = false
        val updatedRooms = house.rooms.mapIndexed { index, room ->
            val template = templates[room.templateId]
            if (template != null && template.maxDroppedItems > 0) {
                val roomId = houseRoomId(house.ownerName, index)
                val currentItems = items.itemsInRoom(roomId)
                if (currentItems != room.storedItems) {
                    changed = true
                    room.copy(storedItems = currentItems)
                } else {
                    room
                }
            } else {
                room
            }
        }
        if (changed) {
            val updated = house.copy(rooms = updatedRooms)
            houseRepo.save(updated)
            housesByOwner[ownerId] = updated
        }
    }

    /**
     * Loads vault items from the house record into the item registry.
     * Called after house materialisation on login.
     */
    private fun loadVaultItems(ownerId: PlayerId) {
        val house = housesByOwner[ownerId] ?: return
        for ((index, room) in house.rooms.withIndex()) {
            val template = templates[room.templateId] ?: continue
            if (template.maxDroppedItems > 0 && room.storedItems.isNotEmpty()) {
                val roomId = houseRoomId(house.ownerName, index)
                for (item in room.storedItems) {
                    items.addRoomItem(roomId, item)
                }
            }
        }
    }

    // -------- status --------

    fun houseStatus(sessionId: SessionId): HouseStatusInfo? {
        val ps = players.get(sessionId) ?: return null
        val pid = ps.playerId ?: return null
        val house = housesByOwner[pid] ?: return null
        return HouseStatusInfo(
            ownerName = house.ownerName,
            rooms = house.rooms.map { room ->
                val tmpl = templates[room.templateId]
                HouseRoomInfo(
                    templateId = room.templateId,
                    title = room.customTitle ?: tmpl?.title ?: room.templateId,
                    description = room.customDescription ?: tmpl?.description ?: "",
                )
            },
        )
    }

    // -------- internals --------

    /** Materialise all rooms for [house] in the World. */
    private fun materializeHouse(house: HouseRecord) {
        val roomMap = mutableMapOf<RoomId, Room>()

        for ((index, roomRecord) in house.rooms.withIndex()) {
            val template = templates[roomRecord.templateId] ?: continue
            val roomId = houseRoomId(house.ownerName, index)

            // Build exits: inter-room exits + the world exit on the entry room
            val exits = mutableMapOf<Direction, RoomId>()
            for ((dir, targetIndex) in roomRecord.exits) {
                exits[dir] = houseRoomId(house.ownerName, targetIndex)
            }
            // Entry room gets the dynamic world exit
            if (index == 0) {
                exits[config.entryExitDirection] = houseExitSentinel(house.ownerName)
            }

            roomMap[roomId] = Room(
                id = roomId,
                title = roomRecord.customTitle ?: template.title,
                description = buildDescription(roomRecord, template, house.ownerName),
                exits = exits,
                station = template.station,
                image = template.image?.let { "$imagesBase$it" },
            )
        }

        world.putRooms(roomMap)
    }

    private fun buildDescription(
        roomRecord: HouseRoomRecord,
        template: RoomTemplate,
        ownerName: String,
    ): String {
        val base = roomRecord.customDescription ?: template.description
        return "$base\n\nThis room belongs to $ownerName's house."
    }

    private fun houseRoomId(ownerName: String, index: Int): RoomId =
        RoomId("house_$ownerName:room_$index")

    private fun houseExitSentinel(ownerName: String): RoomId =
        RoomId("house_$ownerName:$HOUSE_EXIT_LOCAL")

    private fun currentHouseRoomIndex(ps: PlayerState, house: HouseRecord): Int? {
        val roomId = ps.roomId
        if (!roomId.zone.startsWith("house_")) return null
        val local = roomId.local
        if (!local.startsWith("room_")) return null
        val index = local.removePrefix("room_").toIntOrNull() ?: return null
        return if (index in house.rooms.indices) index else null
    }

    private fun updateRoom(
        house: HouseRecord,
        index: Int,
        transform: (HouseRoomRecord) -> HouseRoomRecord,
    ): HouseRecord {
        val updatedRooms = house.rooms.toMutableList()
        updatedRooms[index] = transform(updatedRooms[index])
        return house.copy(rooms = updatedRooms)
    }

    private suspend fun bootVisitor(visitorSessionId: SessionId, message: String) {
        val origin = visitorOrigins.remove(visitorSessionId)
        sessionInHouse.remove(visitorSessionId)
        val vs = players.get(visitorSessionId) ?: return
        val dest = origin ?: vs.recallRoomId ?: world.startRoom
        vs.roomId = dest
        outbound.send(OutboundEvent.SendInfo(visitorSessionId, message))
        outbound.send(OutboundEvent.SendPrompt(visitorSessionId))
    }

    private suspend fun bootAllVisitors(ownerId: PlayerId, message: String) {
        val toRemove = sessionInHouse.entries
            .filter { it.value == ownerId }
            .map { it.key }
            .toList()

        for (sid in toRemove) {
            val ps = players.get(sid) ?: continue
            // Don't boot the owner themselves
            if (ps.playerId == ownerId) {
                visitorOrigins.remove(sid)
                sessionInHouse.remove(sid)
                continue
            }
            bootVisitor(sid, message)
        }
    }
}

data class HouseStatusInfo(
    val ownerName: String,
    val rooms: List<HouseRoomInfo>,
)

data class HouseRoomInfo(
    val templateId: String,
    val title: String,
    val description: String,
)

private fun RoomTemplateConfig.toRoomTemplate(id: String): RoomTemplate =
    RoomTemplate(
        id = id,
        title = title,
        description = description,
        cost = cost,
        isEntry = isEntry,
        image = image,
        maxDroppedItems = maxDroppedItems,
        safe = safe,
        station = station,
    )
