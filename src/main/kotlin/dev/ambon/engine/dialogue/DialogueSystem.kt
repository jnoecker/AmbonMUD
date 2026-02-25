package dev.ambon.engine.dialogue

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent

class DialogueSystem(
    private val mobs: MobRegistry,
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
) {
    data class ConversationState(
        val mobId: MobId,
        val currentNodeId: String,
        val tree: DialogueTree,
        val mobName: String,
    )

    private val conversations = mutableMapOf<SessionId, ConversationState>()

    fun isInConversation(sessionId: SessionId): Boolean = conversations.containsKey(sessionId)

    suspend fun startConversation(
        sessionId: SessionId,
        mobKeyword: String,
    ): String? {
        val player = players.get(sessionId) ?: return "You must be logged in."
        val mob =
            findMobInRoom(player.roomId, mobKeyword)
                ?: return "You don't see '$mobKeyword' here."
        val dialogue =
            mob.dialogue
                ?: return "${mob.name} has nothing to say."

        val rootNode =
            dialogue.nodes[dialogue.rootNodeId]
                ?: return "${mob.name} has nothing to say."

        conversations[sessionId] =
            ConversationState(
                mobId = mob.id,
                currentNodeId = dialogue.rootNodeId,
                tree = dialogue,
                mobName = mob.name,
            )

        renderNode(sessionId, mob.name, rootNode, player.level, player.playerClass)
        return null
    }

    suspend fun selectChoice(
        sessionId: SessionId,
        choiceNumber: Int,
    ): String? {
        val state =
            conversations[sessionId]
                ?: return "You are not in a conversation."
        val player =
            players.get(sessionId)
                ?: return "You must be logged in."

        val currentNode =
            state.tree.nodes[state.currentNodeId]
                ?: return endConversationWithMessage(sessionId, state.mobName)

        val visibleChoices =
            filterChoices(
                currentNode.choices,
                player.level,
                player.playerClass,
            )

        if (visibleChoices.isEmpty()) {
            return endConversationWithMessage(sessionId, state.mobName)
        }

        if (choiceNumber < 1 || choiceNumber > visibleChoices.size) {
            return "Choose a number between 1 and ${visibleChoices.size}."
        }

        val chosen = visibleChoices[choiceNumber - 1]
        val nextNodeId = chosen.nextNodeId

        if (nextNodeId == null) {
            conversations.remove(sessionId)
            outbound.send(
                OutboundEvent.SendText(
                    sessionId,
                    "${state.mobName} nods.",
                ),
            )
            return null
        }

        val nextNode = state.tree.nodes[nextNodeId]
        if (nextNode == null) {
            conversations.remove(sessionId)
            return "${state.mobName} has nothing more to say."
        }

        conversations[sessionId] = state.copy(currentNodeId = nextNodeId)
        renderNode(sessionId, state.mobName, nextNode, player.level, player.playerClass)
        return null
    }

    fun endConversation(sessionId: SessionId) {
        conversations.remove(sessionId)
    }

    fun onPlayerDisconnected(sessionId: SessionId) {
        conversations.remove(sessionId)
    }

    fun onPlayerMoved(sessionId: SessionId) {
        conversations.remove(sessionId)
    }

    fun onMobRemoved(mobId: MobId) {
        val toRemove = conversations.entries.filter { it.value.mobId == mobId }.map { it.key }
        for (sid in toRemove) {
            conversations.remove(sid)
        }
    }

    private suspend fun renderNode(
        sessionId: SessionId,
        mobName: String,
        node: DialogueNode,
        playerLevel: Int,
        playerClass: String,
    ) {
        outbound.send(OutboundEvent.SendText(sessionId, "$mobName says: ${node.text}"))

        val visibleChoices = filterChoices(node.choices, playerLevel, playerClass)
        if (visibleChoices.isEmpty()) {
            conversations.remove(sessionId)
            return
        }

        for ((index, choice) in visibleChoices.withIndex()) {
            outbound.send(
                OutboundEvent.SendInfo(sessionId, "  ${index + 1}. ${choice.text}"),
            )
        }
    }

    private fun filterChoices(
        choices: List<DialogueChoice>,
        playerLevel: Int,
        playerClass: String,
    ): List<DialogueChoice> =
        choices.filter { choice ->
            val levelOk = choice.minLevel == null || playerLevel >= choice.minLevel
            val classOk =
                choice.requiredClass == null ||
                    choice.requiredClass.equals(playerClass, ignoreCase = true)
            levelOk && classOk
        }

    private fun endConversationWithMessage(
        sessionId: SessionId,
        mobName: String,
    ): String {
        conversations.remove(sessionId)
        return "$mobName has nothing more to say."
    }

    private fun findMobInRoom(
        roomId: RoomId,
        keyword: String,
    ) = mobs
        .mobsInRoom(roomId)
        .filter { it.name.lowercase().contains(keyword.lowercase()) }
        .sortedBy { it.name }
        .firstOrNull()
}
