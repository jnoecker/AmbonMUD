package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.AuctionListing
import dev.ambon.engine.AuctionSystem
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.persistence.PlayerRepository

class AuctionHandler(
    private val ctx: EngineContext,
    private val auctionSystem: AuctionSystem? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
    private val playerRepo: PlayerRepository? = null,
) : CommandHandler {
    private val players = ctx.players
    private val items = ctx.items
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.AuctionList> { sid, cmd -> handleList(sid, cmd) }
        router.on<Command.AuctionSell> { sid, cmd -> handleSell(sid, cmd) }
        router.on<Command.AuctionBuy> { sid, cmd -> handleBuy(sid, cmd) }
        router.on<Command.AuctionCancel> { sid, cmd -> handleCancel(sid, cmd) }
    }

    private suspend fun handleList(sessionId: SessionId, cmd: Command.AuctionList) {
        val auction = auctionSystem ?: return sendUnavailable(sessionId)

        val allListings = auction.allListings()
        val filtered = if (cmd.filter != null) {
            val lower = cmd.filter.lowercase()
            allListings.filter {
                it.item.item.displayName.lowercase().contains(lower) ||
                    it.sellerName.lowercase().contains(lower)
            }
        } else {
            allListings
        }

        if (filtered.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "No auction listings found."))
            emitAuctionList(sessionId, emptyList())
            return
        }

        outbound.send(OutboundEvent.SendInfo(sessionId, "[ Auction House ]"))
        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "  %4s  %-25s %8s  %-15s".format("#", "Item", "Price", "Seller"),
            ),
        )
        for (listing in filtered) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  %4d  %-25s %7d  %-15s".format(
                        listing.id,
                        listing.item.item.displayName,
                        listing.price,
                        listing.sellerName,
                    ),
                ),
            )
        }
        outbound.send(
            OutboundEvent.SendInfo(sessionId, "  Use 'auction buy <#>' to purchase."),
        )
        emitAuctionList(sessionId, filtered)
    }

    private suspend fun handleSell(sessionId: SessionId, cmd: Command.AuctionSell) {
        val auction = auctionSystem ?: return sendUnavailable(sessionId)

        if (cmd.price <= 0) {
            outbound.send(OutboundEvent.SendError(sessionId, "Price must be greater than 0."))
            return
        }

        players.withPlayer(sessionId) { me ->
            val listing = auction.postListing(sessionId, me.name, cmd.itemKeyword, cmd.price)
            if (listing == null) {
                outbound.send(
                    OutboundEvent.SendError(sessionId, "You aren't carrying '${cmd.itemKeyword}'."),
                )
                return
            }

            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "Listed ${listing.item.item.displayName} for ${listing.price} gold (listing #${listing.id}).",
                ),
            )
            syncItemsGmcp(sessionId, items, gmcpEmitter)

            // Broadcast to all online players
            for (p in players.allPlayers()) {
                if (p.sessionId == sessionId) continue
                outbound.send(
                    OutboundEvent.SendInfo(
                        p.sessionId,
                        "[Auction] ${me.name} listed ${listing.item.item.displayName} for ${listing.price} gold.",
                    ),
                )
            }

            // Push updated listings to all via GMCP
            broadcastAuctionList(auction)
        }
    }

    private suspend fun handleBuy(sessionId: SessionId, cmd: Command.AuctionBuy) {
        val auction = auctionSystem ?: return sendUnavailable(sessionId)

        players.withPlayer(sessionId) { me ->
            val listing = auction.getListing(cmd.listingId)
            if (listing == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "Listing #${cmd.listingId} not found."))
                return
            }

            if (listing.sellerName.equals(me.name, ignoreCase = true)) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You cannot buy your own listing. Use 'auction cancel ${cmd.listingId}'.",
                    ),
                )
                return
            }

            if (me.gold < listing.price) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You need ${listing.price} gold but only have ${me.gold}.",
                    ),
                )
                return
            }

            // Complete the purchase
            val purchased = auction.completePurchase(cmd.listingId, sessionId)
            if (purchased == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "That listing is no longer available."))
                return
            }

            // Transfer gold: deduct from buyer
            me.gold -= purchased.price

            // Credit seller — online or offline
            val seller = players.getByName(purchased.sellerName)
            if (seller != null) {
                seller.gold += purchased.price
                outbound.send(
                    OutboundEvent.SendInfo(
                        seller.sessionId,
                        "[Auction] ${me.name} purchased your ${purchased.item.item.displayName} for ${purchased.price} gold!",
                    ),
                )
                markVitalsDirty(seller.sessionId)
                syncItemsGmcp(seller.sessionId, items, gmcpEmitter)
            } else {
                // Seller is offline — credit gold via persistence
                creditOfflineSellerGold(purchased.sellerName, purchased.price)
            }

            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "You purchased ${purchased.item.item.displayName} for ${purchased.price} gold.",
                ),
            )
            markVitalsDirty(sessionId)
            syncItemsGmcp(sessionId, items, gmcpEmitter)

            broadcastAuctionList(auction)
        }
    }

    private suspend fun creditOfflineSellerGold(sellerName: String, amount: Long) {
        val repo = playerRepo ?: return
        try {
            val record = repo.findByName(sellerName) ?: return
            repo.save(record.copy(gold = record.gold + amount))
        } catch (e: Exception) {
            io.github.oshai.kotlinlogging.KotlinLogging.logger {}
                .warn(e) { "Failed to credit $amount gold to offline player $sellerName" }
        }
    }

    private suspend fun handleCancel(sessionId: SessionId, cmd: Command.AuctionCancel) {
        val auction = auctionSystem ?: return sendUnavailable(sessionId)

        val cancelled = auction.cancelListing(cmd.listingId, sessionId)
        if (cancelled == null) {
            outbound.send(
                OutboundEvent.SendError(sessionId, "Listing #${cmd.listingId} not found or not yours."),
            )
            return
        }

        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "Cancelled listing #${cancelled.id}. ${cancelled.item.item.displayName} returned to your inventory.",
            ),
        )
        syncItemsGmcp(sessionId, items, gmcpEmitter)

        broadcastAuctionList(auction)
    }

    /** Broadcasts updated auction list to all online players via GMCP. */
    private suspend fun broadcastAuctionList(auction: AuctionSystem) {
        val allListings = auction.allListings()
        val payload = allListings.map { toPayload(it) }
        for (p in players.allPlayers()) {
            gmcpEmitter?.sendAuctionList(p.sessionId, payload)
        }
    }

    private suspend fun emitAuctionList(
        sessionId: SessionId,
        listings: List<AuctionListing>,
    ) {
        gmcpEmitter?.sendAuctionList(sessionId, listings.map { toPayload(it) })
    }

    private fun toPayload(listing: AuctionListing): GmcpEmitter.AuctionListingPayload =
        GmcpEmitter.AuctionListingPayload(
            id = listing.id,
            itemName = listing.item.item.displayName,
            itemId = listing.item.id.value,
            price = listing.price,
            seller = listing.sellerName,
        )

    private suspend fun sendUnavailable(sessionId: SessionId) {
        outbound.send(OutboundEvent.SendError(sessionId, "The auction house is not available."))
    }
}
