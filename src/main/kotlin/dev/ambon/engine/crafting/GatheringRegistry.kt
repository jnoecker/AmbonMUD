package dev.ambon.engine.crafting

import dev.ambon.domain.crafting.GatheringNodeDef
import dev.ambon.domain.ids.RoomId
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

class GatheringRegistry {
    private val nodesById = mutableMapOf<String, GatheringNodeDef>()
    private val nodesByRoom = mutableMapOf<RoomId, MutableList<GatheringNodeDef>>()

    fun register(nodes: List<GatheringNodeDef>) {
        for (node in nodes) {
            nodesById[node.id] = node
            nodesByRoom.getOrPut(node.roomId) { mutableListOf() }.add(node)
        }
        if (nodes.isNotEmpty()) {
            log.info { "Registered ${nodes.size} gathering node(s)" }
        }
    }

    fun nodesInRoom(roomId: RoomId): List<GatheringNodeDef> = nodesByRoom[roomId] ?: emptyList()

    fun nodeById(id: String): GatheringNodeDef? = nodesById[id]

    fun findNodeInRoom(roomId: RoomId, keyword: String): GatheringNodeDef? {
        val nodes = nodesByRoom[roomId] ?: return null
        val lowerKeyword = keyword.lowercase()
        return nodes.firstOrNull { it.keyword.lowercase() == lowerKeyword }
            ?: nodes.firstOrNull { it.keyword.lowercase().startsWith(lowerKeyword) }
            ?: nodes.firstOrNull { it.displayName.lowercase().contains(lowerKeyword) }
    }

    fun allNodes(): Collection<GatheringNodeDef> = nodesById.values

    fun clear() {
        nodesById.clear()
        nodesByRoom.clear()
    }
}
