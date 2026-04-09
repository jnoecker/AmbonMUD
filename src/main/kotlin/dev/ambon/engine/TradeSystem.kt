package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.engine.items.ItemRegistry
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/** An active trade session between two players. */
class TradeSession(
    val initiator: SessionId,
    val target: SessionId,
) {
    val initiatorItems: MutableList<ItemInstance> = mutableListOf()
    val targetItems: MutableList<ItemInstance> = mutableListOf()
    var initiatorGold: Long = 0L
    var targetGold: Long = 0L
    var initiatorAccepted: Boolean = false
    var targetAccepted: Boolean = false

    fun sideOf(sid: SessionId): TradeSide? = when (sid) {
        initiator -> TradeSide.INITIATOR
        target -> TradeSide.TARGET
        else -> null
    }

    fun otherSid(sid: SessionId): SessionId = if (sid == initiator) target else initiator

    fun items(side: TradeSide): MutableList<ItemInstance> = when (side) {
        TradeSide.INITIATOR -> initiatorItems
        TradeSide.TARGET -> targetItems
    }

    fun gold(side: TradeSide): Long = when (side) {
        TradeSide.INITIATOR -> initiatorGold
        TradeSide.TARGET -> targetGold
    }

    fun setGold(side: TradeSide, amount: Long) {
        when (side) {
            TradeSide.INITIATOR -> initiatorGold = amount
            TradeSide.TARGET -> targetGold = amount
        }
    }

    fun setAccepted(side: TradeSide, value: Boolean) {
        when (side) {
            TradeSide.INITIATOR -> initiatorAccepted = value
            TradeSide.TARGET -> targetAccepted = value
        }
    }

    fun isAccepted(side: TradeSide): Boolean = when (side) {
        TradeSide.INITIATOR -> initiatorAccepted
        TradeSide.TARGET -> targetAccepted
    }

    fun bothAccepted(): Boolean = initiatorAccepted && targetAccepted

    /** Reset both accept flags (called when either side modifies the offer). */
    fun resetAcceptance() {
        initiatorAccepted = false
        targetAccepted = false
    }
}

enum class TradeSide { INITIATOR, TARGET }

sealed interface TradeError {
    data object NotInTrade : TradeError

    data object AlreadyInTrade : TradeError

    data object TargetInTrade : TradeError

    data object SelfTrade : TradeError

    data object NotInSameRoom : TradeError

    data object ItemNotFound : TradeError

    data class InsufficientGold(
        val have: Long,
        val need: Long,
    ) : TradeError
}

/** Result of completing a trade atomically (items + gold). */
sealed interface TradeResult {
    data object Success : TradeResult

    data class InsufficientInitiatorGold(
        val have: Long,
        val need: Long,
    ) : TradeResult

    data class InsufficientTargetGold(
        val have: Long,
        val need: Long,
    ) : TradeResult
}

/** Manages active trade sessions between players. */
class TradeSystem(
    private val items: ItemRegistry,
) {
    private val sessions = mutableMapOf<SessionId, TradeSession>()

    fun getSession(sid: SessionId): TradeSession? = sessions[sid]

    fun isInTrade(sid: SessionId): Boolean = sid in sessions

    /** Creates a trade session. Both players must not already be in a trade. */
    fun createSession(initiator: SessionId, target: SessionId): TradeSession? {
        if (initiator in sessions) return null
        if (target in sessions) return null
        val session = TradeSession(initiator, target)
        sessions[initiator] = session
        sessions[target] = session
        log.debug { "Trade session created: $initiator <-> $target" }
        return session
    }

    /**
     * Adds an item from the player's inventory to their trade offer.
     * The item is removed from inventory immediately (held in escrow).
     * Returns the item on success, or null if not found.
     */
    fun offerItem(sid: SessionId, keyword: String): Pair<TradeSession, ItemInstance>? {
        val session = sessions[sid] ?: return null
        val side = session.sideOf(sid) ?: return null
        val item = items.removeFromInventory(sid, keyword) ?: return null
        session.items(side).add(item)
        session.resetAcceptance()
        return session to item
    }

    /**
     * Removes an escrowed item from the player's offer and returns it to inventory.
     * Matches by instance id first, then by keyword/display name for text-command parity.
     */
    fun removeOfferedItem(sid: SessionId, itemRef: String): Pair<TradeSession, ItemInstance>? {
        val session = sessions[sid] ?: return null
        val side = session.sideOf(sid) ?: return null
        val offeredItems = session.items(side)
        val loweredRef = itemRef.trim().lowercase()
        val matchIndex = offeredItems.indexOfFirst { item ->
            item.id.value.equals(itemRef, ignoreCase = true) ||
                item.item.keyword.equals(itemRef, ignoreCase = true) ||
                item.item.displayName.equals(itemRef, ignoreCase = true) ||
                item.item.displayName.lowercase().contains(loweredRef)
        }
        if (matchIndex < 0) return null

        val item = offeredItems.removeAt(matchIndex)
        items.addToInventory(sid, item)
        session.resetAcceptance()
        return session to item
    }

    /**
     * Sets the gold amount offered by the player.
     * Returns the session on success, or a TradeError.
     */
    fun offerGold(sid: SessionId, amount: Long, playerGold: Long): Pair<TradeSession?, TradeError?> {
        val session = sessions[sid] ?: return null to TradeError.NotInTrade
        val side = session.sideOf(sid) ?: return null to TradeError.NotInTrade
        if (amount > playerGold) {
            return null to TradeError.InsufficientGold(playerGold, amount)
        }
        session.setGold(side, amount)
        session.resetAcceptance()
        return session to null
    }

    /** Marks the player as having accepted the current offer. */
    fun accept(sid: SessionId): TradeSession? {
        val session = sessions[sid] ?: return null
        val side = session.sideOf(sid) ?: return null
        session.setAccepted(side, true)
        return session
    }

    /**
     * Completes the trade atomically: re-validates gold, then transfers items and gold
     * between players in a single method call.
     * Only call when bothAccepted() is true.
     *
     * @param initiatorGold current gold of the initiator (for re-validation)
     * @param targetGold current gold of the target (for re-validation)
     * @param deductInitiatorGold callback to mutate the initiator's gold by a delta
     * @param deductTargetGold callback to mutate the target's gold by a delta
     * @return [TradeResult] indicating success or which party lacked gold
     */
    fun complete(
        session: TradeSession,
        initiatorGold: Long,
        targetGold: Long,
        deductInitiatorGold: (Long) -> Unit,
        deductTargetGold: (Long) -> Unit,
    ): TradeResult {
        // Re-validate gold at completion time (player may have spent gold after offering)
        if (session.initiatorGold > 0 && initiatorGold < session.initiatorGold) {
            return TradeResult.InsufficientInitiatorGold(initiatorGold, session.initiatorGold)
        }
        if (session.targetGold > 0 && targetGold < session.targetGold) {
            return TradeResult.InsufficientTargetGold(targetGold, session.targetGold)
        }

        // Transfer gold atomically: deduct offered, add received
        val initiatorNet = session.targetGold - session.initiatorGold
        val targetNet = session.initiatorGold - session.targetGold
        deductInitiatorGold(initiatorNet)
        deductTargetGold(targetNet)

        // Transfer items: initiator's offered items → target's inventory
        for (item in session.initiatorItems) {
            items.addToInventory(session.target, item)
        }
        // Transfer items: target's offered items → initiator's inventory
        for (item in session.targetItems) {
            items.addToInventory(session.initiator, item)
        }

        cleanup(session)
        log.debug { "Trade completed: ${session.initiator} <-> ${session.target}" }
        return TradeResult.Success
    }

    /**
     * Cancels a trade and returns all escrowed items to their owners.
     */
    fun cancel(session: TradeSession) {
        // Return items to their original owners
        for (item in session.initiatorItems) {
            items.addToInventory(session.initiator, item)
        }
        for (item in session.targetItems) {
            items.addToInventory(session.target, item)
        }
        cleanup(session)
        log.debug { "Trade cancelled: ${session.initiator} <-> ${session.target}" }
    }

    /** Cancels any active trade for a player (called on disconnect). */
    fun cancelForPlayer(sid: SessionId): TradeSession? {
        val session = sessions[sid] ?: return null
        cancel(session)
        return session
    }

    private fun cleanup(session: TradeSession) {
        sessions.remove(session.initiator)
        sessions.remove(session.target)
    }

    fun clear() {
        sessions.clear()
    }
}
