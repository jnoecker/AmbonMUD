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
     * Completes the trade: transfers items and gold between players.
     * Only call when bothAccepted() is true.
     */
    fun complete(session: TradeSession) {
        // Transfer items: initiator's offered items → target's inventory
        for (item in session.initiatorItems) {
            items.addToInventory(session.target, item)
        }
        // Transfer items: target's offered items → initiator's inventory
        for (item in session.targetItems) {
            items.addToInventory(session.initiator, item)
        }
        // Gold is handled by the caller (PlayerState mutation)
        cleanup(session)
        log.debug { "Trade completed: ${session.initiator} <-> ${session.target}" }
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
