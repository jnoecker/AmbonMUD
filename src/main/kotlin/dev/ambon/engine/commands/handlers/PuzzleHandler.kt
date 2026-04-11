package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.puzzle.PuzzleReward
import dev.ambon.domain.world.LockableState
import dev.ambon.domain.world.RoomFeature
import dev.ambon.engine.PuzzleResult
import dev.ambon.engine.PuzzleSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

/**
 * Handles puzzle-related commands:
 * - `answer <text>` — attempt to answer a riddle puzzle in the current room.
 * - `pull <lever>` — intercepted to advance sequence puzzles when applicable.
 */
class PuzzleHandler(
    private val ctx: EngineContext,
    private val puzzleSystem: PuzzleSystem?,
) : CommandHandler {
    private val world = ctx.world
    private val players = ctx.players
    private val items = ctx.items
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Answer> { sid, cmd -> handleAnswer(sid, cmd.text) }
    }

    private suspend fun handleAnswer(
        sessionId: SessionId,
        answerText: String,
    ) {
        val system = puzzleSystem
        if (system == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Puzzles are not available."))
            return
        }

        val me = players.get(sessionId) ?: return
        val riddles = system.riddlesInRoom(me.roomId)

        if (riddles.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, "There is no puzzle to answer here."))
            return
        }

        // Try each riddle in the room — if any succeeds, stop immediately.
        // Otherwise, show the fail message from the last attempted riddle.
        var lastFailMessage: String? = null
        for (puzzle in riddles) {
            val result = system.attemptRiddleAnswer(sessionId, puzzle, answerText)
            when (result) {
                is PuzzleResult.Success -> {
                    outbound.send(OutboundEvent.SendInfo(sessionId, result.message))
                    grantReward(sessionId, result.reward)
                    // Refresh room state so newly-unlocked exits, minimap, and Puzzle.List
                    // (marking the puzzle as solved) reach the client immediately.
                    ctx.sendLook(sessionId)
                    return
                }
                is PuzzleResult.Failure -> {
                    lastFailMessage = result.message
                    // Continue to try next riddle
                }
                is PuzzleResult.AlreadySolved -> {
                    // Skip this one, try others
                }
                is PuzzleResult.OnCooldown -> {
                    // Skip this one, try others
                }
                is PuzzleResult.NotApplicable,
                is PuzzleResult.SequenceAdvanced,
                -> continue
            }
        }

        // No riddle succeeded — show last fail or generic message
        if (lastFailMessage != null) {
            outbound.send(OutboundEvent.SendError(sessionId, lastFailMessage))
        } else {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You have already solved this puzzle."))
        }
    }

    /**
     * Called by WorldFeaturesHandler when a lever is pulled, to check for sequence puzzle advancement.
     */
    suspend fun onFeatureInteraction(
        sessionId: SessionId,
        featureKeyword: String,
        action: String,
    ): Boolean {
        val system = puzzleSystem ?: return false
        val me = players.get(sessionId) ?: return false

        val result = system.advanceSequence(sessionId, me.roomId, featureKeyword, action)
            ?: return false

        when (result) {
            is PuzzleResult.Success -> {
                outbound.send(OutboundEvent.SendInfo(sessionId, result.message))
                grantReward(sessionId, result.reward)
                // Refresh room state so newly-unlocked exits and Puzzle.List reach the client.
                ctx.sendLook(sessionId)
            }
            is PuzzleResult.Failure -> {
                outbound.send(OutboundEvent.SendError(sessionId, result.message))
            }
            is PuzzleResult.SequenceAdvanced -> {
                // Silently advance — the normal lever pull message is shown by WorldFeaturesHandler.
                // Re-emit Puzzle.List so the client can update its progress indicator.
                ctx.emitPuzzleGmcp(sessionId)
            }
            is PuzzleResult.AlreadySolved,
            is PuzzleResult.OnCooldown,
            is PuzzleResult.NotApplicable,
            -> Unit
        }
        return true
    }

    private suspend fun grantReward(sessionId: SessionId, reward: PuzzleReward) {
        val me = players.get(sessionId) ?: return
        when (reward) {
            is PuzzleReward.UnlockExit -> {
                // Exit is already unlocked in PuzzleSystem state; inform the player
                val dirName = reward.direction.name.lowercase()
                outbound.send(
                    OutboundEvent.SendInfo(sessionId, "A passage to the $dirName has been revealed!"),
                )
                broadcastToRoomExcept(
                    me.roomId,
                    sessionId,
                    "A passage to the $dirName has been revealed!",
                    players,
                    outbound,
                )
            }
            is PuzzleReward.GiveItem -> {
                val itemId = ItemId(reward.itemId)
                val instance = items.createFromTemplate(itemId)
                if (instance != null) {
                    items.addToInventory(sessionId, instance)
                    outbound.send(
                        OutboundEvent.SendInfo(sessionId, "You receive ${instance.item.displayName}."),
                    )
                    syncItemsGmcp(sessionId, items, gmcpEmitter)
                    // If the key opens a locked feature in the current room, auto-unlock
                    // it so the player can proceed without manually fiddling with locks.
                    autoUnlockWithKey(sessionId, me.roomId, itemId)
                } else {
                    outbound.send(
                        OutboundEvent.SendError(
                            sessionId,
                            "The reward could not be granted (item '${reward.itemId}' not found).",
                        ),
                    )
                }
            }
            is PuzzleReward.GiveGold -> {
                me.gold += reward.amount
                outbound.send(OutboundEvent.SendText(sessionId, "You receive ${reward.amount} gold."))
            }
            is PuzzleReward.GiveXp -> {
                players.grantXp(sessionId, reward.amount)
                outbound.send(OutboundEvent.SendText(sessionId, "You gain ${reward.amount} XP."))
            }
        }
    }

    /**
     * When a puzzle grants a key, any door or container in the player's current room
     * that requires that key is automatically opened so the player can proceed.
     * Consumable keys are removed from the inventory as if the player had manually unlocked the lock.
     */
    private suspend fun autoUnlockWithKey(
        sessionId: SessionId,
        roomId: RoomId,
        keyItemId: ItemId,
    ) {
        val room = world.rooms[roomId] ?: return
        val state = ctx.worldState ?: return
        for (feature in room.features) {
            val (featureKeyItemId, keyConsumed, displayName) = when (feature) {
                is RoomFeature.Door -> Triple(feature.keyItemId, feature.keyConsumed, feature.displayName)
                is RoomFeature.Container -> Triple(feature.keyItemId, feature.keyConsumed, feature.displayName)
                else -> continue
            }
            if (featureKeyItemId != keyItemId) continue
            if (state.getLockableState(feature.id) == LockableState.OPEN) continue
            state.setLockableState(feature.id, LockableState.OPEN)
            if (keyConsumed) {
                items.removeFromInventoryById(sessionId, keyItemId)
                syncItemsGmcp(sessionId, items, gmcpEmitter)
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "The $displayName swings open."))
            broadcastToRoomExcept(
                roomId,
                sessionId,
                "The $displayName swings open.",
                players,
                outbound,
            )
        }
    }
}
