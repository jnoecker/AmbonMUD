package dev.ambon.engine

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.atomicWriteText
import io.github.oshai.kotlinlogging.KotlinLogging
import java.nio.file.Files
import java.nio.file.Path
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

/** JSON DTO for persisting auction listings. */
private data class PersistedListing(
    val id: Int = 0,
    val sellerName: String = "",
    val itemId: String = "",
    val itemKeyword: String = "",
    val itemDisplayName: String = "",
    val price: Long = 0L,
    val expiresAtMs: Long = 0L,
)

/** Manages the auction house / player marketplace. */
class AuctionSystem(
    private val items: ItemRegistry,
    private val clock: Clock,
    val listingDurationMs: Long = 3_600_000L,
    private val persistPath: Path? = null,
) {
    private var nextId = 1
    private val listings = mutableMapOf<Int, AuctionListing>()

    fun allListings(): List<AuctionListing> =
        listings.values.sortedBy { it.id }

    fun getListing(id: Int): AuctionListing? = listings[id]

    fun listingsBy(sellerName: String): List<AuctionListing> =
        listings.values.filter { it.sellerName.equals(sellerName, ignoreCase = true) }

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
        persist()
        return listing
    }

    /**
     * Completes a purchase: transfers item to buyer. Gold must be handled by caller.
     */
    fun completePurchase(listingId: Int, buyerSid: SessionId): AuctionListing? {
        val listing = listings.remove(listingId) ?: return null
        items.addToInventory(buyerSid, listing.item)
        log.debug { "Auction listing #$listingId purchased by $buyerSid" }
        persist()
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
        persist()
        return listing
    }

    /**
     * Expires all listings past their deadline, returning items to sellers.
     * Returns the expired listings for notification.
     */
    fun expireListings(): List<AuctionListing> {
        val now = clock.millis()
        val expired = listings.values.filter { it.expiresAtMs <= now }
        if (expired.isEmpty()) return emptyList()
        for (listing in expired) {
            listings.remove(listing.id)
            items.addToInventory(listing.sellerSid, listing.item)
            log.debug { "Auction listing #${listing.id} expired, item returned to ${listing.sellerName}" }
        }
        persist()
        return expired
    }

    /** Cancels all listings by a player (called on disconnect). Returns cancelled listings. */
    fun cancelAllForPlayer(sid: SessionId): List<AuctionListing> {
        val playerListings = listings.values.filter { it.sellerSid == sid }
        if (playerListings.isEmpty()) return emptyList()
        for (listing in playerListings) {
            listings.remove(listing.id)
            items.addToInventory(listing.sellerSid, listing.item)
        }
        persist()
        return playerListings
    }

    /** Loads persisted listings from disk. Call on startup after items are loaded. */
    fun loadPersistedListings() {
        val path = persistPath ?: return
        if (!Files.exists(path)) return
        try {
            val mapper = jacksonObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            val persisted: List<PersistedListing> = mapper.readValue(Files.readString(path))
            for (pl in persisted) {
                if (pl.expiresAtMs <= clock.millis()) continue // already expired
                val itemInstance = ItemInstance(
                    id = ItemId(pl.itemId),
                    item = Item(keyword = pl.itemKeyword, displayName = pl.itemDisplayName),
                )
                val listing = AuctionListing(
                    id = pl.id,
                    sellerSid = SessionId(0L), // offline — will be matched by name
                    sellerName = pl.sellerName,
                    item = itemInstance,
                    price = pl.price,
                    expiresAtMs = pl.expiresAtMs,
                )
                listings[listing.id] = listing
                if (listing.id >= nextId) nextId = listing.id + 1
            }
            log.info { "Loaded ${listings.size} persisted auction listings" }
        } catch (e: Exception) {
            log.warn(e) { "Failed to load auction listings from $path" }
        }
    }

    private fun persist() {
        val path = persistPath ?: return
        try {
            val mapper = jacksonObjectMapper()
            val persisted = listings.values.map { listing ->
                PersistedListing(
                    id = listing.id,
                    sellerName = listing.sellerName,
                    itemId = listing.item.id.value,
                    itemKeyword = listing.item.item.keyword,
                    itemDisplayName = listing.item.item.displayName,
                    price = listing.price,
                    expiresAtMs = listing.expiresAtMs,
                )
            }
            atomicWriteText(path, mapper.writeValueAsString(persisted))
        } catch (e: Exception) {
            log.warn(e) { "Failed to persist auction listings to $path" }
        }
    }

    fun clear() {
        listings.clear()
        nextId = 1
    }
}
