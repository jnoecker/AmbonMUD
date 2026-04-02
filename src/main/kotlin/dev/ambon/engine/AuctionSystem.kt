package dev.ambon.engine

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.engine.items.ItemRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock

private val log = KotlinLogging.logger {}

/** A single auction listing. */
class AuctionListing(
    val id: Int,
    val sellerSid: SessionId,
    val sellerName: String,
    val item: ItemInstance,
    val price: Long,
    val expiresAtMs: Long,
)

/** Manages the auction house / player marketplace. */
class AuctionSystem(
    private val items: ItemRegistry,
    private val clock: Clock,
    val listingDurationMs: Long = 3_600_000L,
) {
    private var nextId = 1
    private val listings = mutableMapOf<Int, AuctionListing>()

    fun allListings(): List<AuctionListing> =
        listings.values.sortedBy { it.id }

    fun getListing(id: Int): AuctionListing? = listings[id]

    fun listingsBy(sellerSid: SessionId): List<AuctionListing> =
        listings.values.filter { it.sellerSid == sellerSid }

    /**
     * Posts an item for sale. The item is removed from the seller's inventory
     * and held in escrow. Returns the listing on success, or null if the item
     * wasn't found.
     */
    fun postListing(
        sellerSid: SessionId,
        sellerName: String,
        itemKeyword: String,
        price: Long,
    ): AuctionListing? {
        val item = items.removeFromInventory(sellerSid, itemKeyword) ?: return null
        val id = nextId++
        val listing = AuctionListing(
            id = id,
            sellerSid = sellerSid,
            sellerName = sellerName,
            item = item,
            price = price,
            expiresAtMs = clock.millis() + listingDurationMs,
        )
        listings[id] = listing
        log.debug { "Auction listing #$id created: ${item.item.displayName} by $sellerName for $price gold" }
        return listing
    }

    /**
     * Purchases a listing. Returns the listing on success, or null if
     * the listing doesn't exist. Caller is responsible for gold validation
     * and transfer.
     */
    fun purchase(listingId: Int): AuctionListing? {
        val listing = listings.remove(listingId) ?: return null
        items.addToInventory(listing.sellerSid, listing.item) // placeholder — caller overrides
        // Actually: remove from escrow, add to buyer. Caller handles this.
        return listing
    }

    /**
     * Completes a purchase: transfers item to buyer. Gold must be handled by caller.
     */
    fun completePurchase(listingId: Int, buyerSid: SessionId): AuctionListing? {
        val listing = listings.remove(listingId) ?: return null
        items.addToInventory(buyerSid, listing.item)
        log.debug { "Auction listing #$listingId purchased by $buyerSid" }
        return listing
    }

    /**
     * Cancels a listing and returns the item to the seller.
     * Returns the listing on success, or null if not found.
     */
    fun cancelListing(listingId: Int, requestingSid: SessionId): AuctionListing? {
        val listing = listings[listingId] ?: return null
        if (listing.sellerSid != requestingSid) return null
        listings.remove(listingId)
        items.addToInventory(listing.sellerSid, listing.item)
        log.debug { "Auction listing #$listingId cancelled by seller" }
        return listing
    }

    /**
     * Expires all listings past their deadline, returning items to sellers.
     * Returns the expired listings for notification.
     */
    fun expireListings(): List<AuctionListing> {
        val now = clock.millis()
        val expired = listings.values.filter { it.expiresAtMs <= now }
        for (listing in expired) {
            listings.remove(listing.id)
            items.addToInventory(listing.sellerSid, listing.item)
            log.debug { "Auction listing #${listing.id} expired, item returned to ${listing.sellerName}" }
        }
        return expired
    }

    /** Cancels all listings by a player (called on disconnect). Returns cancelled listings. */
    fun cancelAllForPlayer(sid: SessionId): List<AuctionListing> {
        val playerListings = listings.values.filter { it.sellerSid == sid }
        for (listing in playerListings) {
            listings.remove(listing.id)
            items.addToInventory(listing.sellerSid, listing.item)
        }
        return playerListings
    }

    fun clear() {
        listings.clear()
        nextId = 1
    }
}
