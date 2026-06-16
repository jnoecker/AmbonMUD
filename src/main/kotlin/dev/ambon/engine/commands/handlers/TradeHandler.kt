package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.TradeResult
import dev.ambon.engine.TradeSession
import dev.ambon.engine.TradeSide
import dev.ambon.engine.TradeSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class TradeHandler(
    ctx: EngineContext,
    private val tradeSystem: TradeSystem? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
) : CommandHandler {
    private val players = ctx.players
    private val items = ctx.items
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter
    private val metrics = ctx.metrics

    override fun register(router: CommandRouter) {
        router.on<Command.TradeRequest> { sid, cmd -> handleTradeRequest(sid, cmd) }
        router.on<Command.TradeOffer> { sid, cmd -> handleTradeOffer(sid, cmd) }
        router.on<Command.TradeOfferGold> { sid, cmd -> handleTradeOfferGold(sid, cmd) }
        router.on<Command.TradeRemove> { sid, cmd -> handleTradeRemove(sid, cmd) }
        router.on<Command.TradeAccept> { sid, _ -> handleTradeAccept(sid) }
        router.on<Command.TradeCancel> { sid, _ -> handleTradeCancel(sid) }
        router.on<Command.TradeStatus> { sid, _ -> handleTradeStatus(sid) }
    }

    private suspend fun handleTradeRequest(sessionId: SessionId, cmd: Command.TradeRequest) {
        if (!requireClaimed(sessionId, players, outbound, "Trading", gmcpEmitter, "trade", "request")) return
        val ts = tradeSystem ?: return sendUnavailable(sessionId)

        players.withPlayer(sessionId) { me ->
            if (ts.isInTrade(sessionId)) {
                outbound.send(OutboundEvent.SendError(sessionId, "You are already in a trade. Type 'trade cancel' first."))
                return
            }

            val targetSid = requirePlayerOnline(sessionId, cmd.playerName, players, outbound) ?: return

            if (targetSid == sessionId) {
                outbound.send(OutboundEvent.SendError(sessionId, "You cannot trade with yourself."))
                return
            }

            players.withPlayer(targetSid) { target ->
                if (!requireSameRoom(sessionId, me, target, outbound)) return

                if (ts.isInTrade(targetSid)) {
                    outbound.send(OutboundEvent.SendError(sessionId, "${target.name} is already in another trade."))
                    return
                }

                val session = ts.createSession(sessionId, targetSid)
                if (session == null) {
                    outbound.send(OutboundEvent.SendError(sessionId, "Could not start trade."))
                    return
                }

                outbound.send(OutboundEvent.SendInfo(sessionId, "You initiate a trade with ${target.name}."))
                outbound.send(
                    OutboundEvent.SendInfo(
                        targetSid,
                        "${me.name} wants to trade with you. Use 'trade offer <item>' or 'trade offer <amount> gold' to add items, 'trade accept' to confirm, or 'trade cancel' to decline.",
                    ),
                )
                emitTradeState(session)
            }
        }
    }

    private suspend fun handleTradeOffer(sessionId: SessionId, cmd: Command.TradeOffer) {
        val ts = tradeSystem ?: return sendUnavailable(sessionId)

        if (!ts.isInTrade(sessionId)) {
            outbound.send(OutboundEvent.SendError(sessionId, "You are not in a trade."))
            return
        }

        val carried = items.peekInventoryItem(sessionId, cmd.itemKeyword)
        if (carried != null && carried.item.isBound()) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "You can't trade ${carried.item.displayName} — it's a ${carried.item.boundKind()}.",
                ),
            )
            return
        }
        val result = ts.offerItem(sessionId, cmd.itemKeyword)
        if (result == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "You aren't carrying '${cmd.itemKeyword}'."))
            return
        }

        val (session, item) = result
        val otherSid = session.otherSid(sessionId)
        val myName = players.get(sessionId)?.name ?: "Someone"

        outbound.send(OutboundEvent.SendInfo(sessionId, "You offer ${item.item.displayName} in the trade."))
        outbound.send(OutboundEvent.SendInfo(otherSid, "$myName offers ${item.item.displayName} in the trade."))
        gmcpEmitter?.sendCharItemsRemove(sessionId, item)
        syncItemsGmcp(sessionId, items, gmcpEmitter)
        emitTradeState(session)
    }

    private suspend fun handleTradeOfferGold(sessionId: SessionId, cmd: Command.TradeOfferGold) {
        val ts = tradeSystem ?: return sendUnavailable(sessionId)

        players.withPlayer(sessionId) { me ->
            val (session, error) = ts.offerGold(sessionId, cmd.amount, me.gold)
            if (error != null) {
                when (error) {
                    is dev.ambon.engine.TradeError.NotInTrade ->
                        outbound.send(OutboundEvent.SendError(sessionId, "You are not in a trade."))
                    is dev.ambon.engine.TradeError.InsufficientGold ->
                        outbound.send(OutboundEvent.SendError(sessionId, "You only have ${error.have} gold."))
                    else ->
                        outbound.send(OutboundEvent.SendError(sessionId, "Trade error."))
                }
                return
            }

            if (session != null) {
                val otherSid = session.otherSid(sessionId)
                outbound.send(OutboundEvent.SendInfo(sessionId, "You offer ${cmd.amount} gold in the trade."))
                outbound.send(
                    OutboundEvent.SendInfo(otherSid, "${me.name} offers ${cmd.amount} gold in the trade."),
                )
                emitTradeState(session)
            }
        }
    }

    private suspend fun handleTradeRemove(sessionId: SessionId, cmd: Command.TradeRemove) {
        val ts = tradeSystem ?: return sendUnavailable(sessionId)

        if (!ts.isInTrade(sessionId)) {
            outbound.send(OutboundEvent.SendError(sessionId, "You are not in a trade."))
            return
        }

        val result = ts.removeOfferedItem(sessionId, cmd.itemRef)
        if (result == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "That item is not part of your current offer."))
            return
        }

        val (session, item) = result
        val otherSid = session.otherSid(sessionId)
        val myName = players.get(sessionId)?.name ?: "Someone"

        outbound.send(OutboundEvent.SendInfo(sessionId, "You remove ${item.item.displayName} from the trade."))
        outbound.send(OutboundEvent.SendInfo(otherSid, "$myName removes ${item.item.displayName} from the trade."))
        syncItemsGmcp(sessionId, items, gmcpEmitter)
        emitTradeState(session)
    }

    private suspend fun handleTradeAccept(sessionId: SessionId) {
        val ts = tradeSystem ?: return sendUnavailable(sessionId)

        val session = ts.accept(sessionId)
        if (session == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "You are not in a trade."))
            return
        }

        val otherSid = session.otherSid(sessionId)
        val myName = players.get(sessionId)?.name ?: "Someone"

        if (session.bothAccepted()) {
            completeTrade(session, ts)
        } else {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You accept the trade. Waiting for the other player."))
            outbound.send(OutboundEvent.SendInfo(otherSid, "$myName has accepted the trade. Type 'trade accept' to confirm."))
            emitTradeState(session)
        }
    }

    private suspend fun completeTrade(session: TradeSession, ts: TradeSystem) {
        val initSid = session.initiator
        val targetSid = session.target

        val initPlayer = players.get(initSid)
        val targetPlayer = players.get(targetSid)
        if (initPlayer == null || targetPlayer == null) {
            ts.cancel(session)
            return
        }

        // Atomic completion: gold re-validation + transfer + item transfer all inside TradeSystem
        val result = ts.complete(
            session = session,
            initiatorGold = initPlayer.gold,
            targetGold = targetPlayer.gold,
            deductInitiatorGold = { delta ->
                initPlayer.gold = (initPlayer.gold + delta).coerceAtLeast(0)
            },
            deductTargetGold = { delta ->
                targetPlayer.gold = (targetPlayer.gold + delta).coerceAtLeast(0)
            },
        )

        when (result) {
            is TradeResult.InsufficientInitiatorGold -> {
                outbound.send(OutboundEvent.SendError(initSid, "You no longer have enough gold. Trade cancelled."))
                outbound.send(OutboundEvent.SendError(targetSid, "Trade cancelled — ${initPlayer.name} doesn't have enough gold."))
                ts.cancel(session)
                syncBothPlayers(initSid, targetSid)
                return
            }
            is TradeResult.InsufficientTargetGold -> {
                outbound.send(OutboundEvent.SendError(targetSid, "You no longer have enough gold. Trade cancelled."))
                outbound.send(OutboundEvent.SendError(initSid, "Trade cancelled — ${targetPlayer.name} doesn't have enough gold."))
                ts.cancel(session)
                syncBothPlayers(initSid, targetSid)
                return
            }
            is TradeResult.Success -> { /* fall through to summary */ }
        }

        metrics.onGameEvent("trade", "completed")

        // Build summary
        val initItemNames = session.initiatorItems.joinToString(", ") { it.item.displayName }.ifEmpty { "nothing" }
        val targetItemNames = session.targetItems.joinToString(", ") { it.item.displayName }.ifEmpty { "nothing" }
        val initGoldStr = if (session.initiatorGold > 0) " and ${session.initiatorGold} gold" else ""
        val targetGoldStr = if (session.targetGold > 0) " and ${session.targetGold} gold" else ""

        outbound.send(
            OutboundEvent.SendInfo(
                initSid,
                "** Trade complete! You gave $initItemNames$initGoldStr and received $targetItemNames$targetGoldStr. **",
            ),
        )
        outbound.send(
            OutboundEvent.SendInfo(
                targetSid,
                "** Trade complete! You gave $targetItemNames$targetGoldStr and received $initItemNames$initGoldStr. **",
            ),
        )

        emitTradeClosed(initSid)
        emitTradeClosed(targetSid)
        syncBothPlayers(initSid, targetSid)
    }

    private suspend fun handleTradeCancel(sessionId: SessionId) {
        val ts = tradeSystem ?: return sendUnavailable(sessionId)

        val session = ts.cancelForPlayer(sessionId)
        if (session == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "You are not in a trade."))
            return
        }

        val otherSid = session.otherSid(sessionId)
        val myName = players.get(sessionId)?.name ?: "Someone"

        outbound.send(OutboundEvent.SendInfo(sessionId, "You cancel the trade."))
        outbound.send(OutboundEvent.SendInfo(otherSid, "$myName cancelled the trade."))

        emitTradeClosed(sessionId)
        emitTradeClosed(otherSid)
        syncBothPlayers(sessionId, otherSid)
    }

    private suspend fun handleTradeStatus(sessionId: SessionId) {
        val ts = tradeSystem ?: return sendUnavailable(sessionId)

        val session = ts.getSession(sessionId)
        if (session == null) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "You are not in a trade. Use 'trade <player>' to start one.",
                ),
            )
            return
        }

        val side = session.sideOf(sessionId)!!
        val otherSide = if (side == dev.ambon.engine.TradeSide.INITIATOR) {
            dev.ambon.engine.TradeSide.TARGET
        } else {
            dev.ambon.engine.TradeSide.INITIATOR
        }
        val otherName = players.get(session.otherSid(sessionId))?.name ?: "Unknown"

        outbound.send(OutboundEvent.SendInfo(sessionId, "[ Trade with $otherName ]"))

        outbound.send(OutboundEvent.SendInfo(sessionId, "  Your offer:"))
        for (item in session.items(side)) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "    - ${item.item.displayName}"))
        }
        val myGold = session.gold(side)
        if (myGold > 0) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "    - $myGold gold"))
        }
        if (session.items(side).isEmpty() && myGold == 0L) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "    (nothing)"))
        }

        outbound.send(OutboundEvent.SendInfo(sessionId, "  Their offer:"))
        for (item in session.items(otherSide)) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "    - ${item.item.displayName}"))
        }
        val theirGold = session.gold(otherSide)
        if (theirGold > 0) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "    - $theirGold gold"))
        }
        if (session.items(otherSide).isEmpty() && theirGold == 0L) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "    (nothing)"))
        }

        val myStatus = if (session.isAccepted(side)) "ACCEPTED" else "pending"
        val theirStatus = if (session.isAccepted(otherSide)) "ACCEPTED" else "pending"
        outbound.send(OutboundEvent.SendInfo(sessionId, "  Status: You=$myStatus, $otherName=$theirStatus"))
    }

    private suspend fun emitTradeState(session: TradeSession) {
        for (sid in listOf(session.initiator, session.target)) {
            val side = session.sideOf(sid) ?: continue
            val otherSide = if (side == TradeSide.INITIATOR) TradeSide.TARGET else TradeSide.INITIATOR
            val partnerName = players.get(session.otherSid(sid))?.name
            gmcpEmitter?.sendTradeState(
                sid,
                GmcpEmitter.TradeStatePayload(
                    active = true,
                    partner = partnerName,
                    myItems = session.items(side).map { GmcpEmitter.TradeItemPayload(it.id.value, it.item.displayName) },
                    theirItems = session.items(otherSide).map { GmcpEmitter.TradeItemPayload(it.id.value, it.item.displayName) },
                    myGold = session.gold(side),
                    theirGold = session.gold(otherSide),
                    myAccepted = session.isAccepted(side),
                    theirAccepted = session.isAccepted(otherSide),
                ),
            )
        }
    }

    private suspend fun emitTradeClosed(sid: SessionId) {
        gmcpEmitter?.sendTradeState(
            sid,
            GmcpEmitter.TradeStatePayload(
                active = false,
                partner = null,
                myItems = emptyList(),
                theirItems = emptyList(),
                myGold = 0,
                theirGold = 0,
                myAccepted = false,
                theirAccepted = false,
            ),
        )
    }

    private suspend fun syncBothPlayers(sid1: SessionId, sid2: SessionId) {
        syncItemsGmcp(sid1, items, gmcpEmitter)
        syncItemsGmcp(sid2, items, gmcpEmitter)
        markVitalsDirty(sid1)
        markVitalsDirty(sid2)
    }

    private suspend fun sendUnavailable(sessionId: SessionId) {
        outbound.send(OutboundEvent.SendError(sessionId, "Trading is not available."))
    }
}
