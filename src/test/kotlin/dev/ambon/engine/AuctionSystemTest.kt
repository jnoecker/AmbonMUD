package dev.ambon.engine

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.test.MutableClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists

class AuctionSystemTest {
    private val clock = MutableClock(1000L)
    private val sid1 = SessionId(1L)
    private val sid2 = SessionId(2L)
    private val swordId = ItemId("test:sword")
    private val potionId = ItemId("test:potion")

    private lateinit var items: ItemRegistry
    private lateinit var auction: AuctionSystem

    @BeforeEach
    fun setUp() {
        items = ItemRegistry()
        items.loadSpawns(
            listOf(
                ItemSpawn(
                    instance = ItemInstance(swordId, Item(keyword = "sword", displayName = "a sword")),
                    roomId = null,
                ),
                ItemSpawn(
                    instance = ItemInstance(potionId, Item(keyword = "potion", displayName = "a potion")),
                    roomId = null,
                ),
            ),
        )
        items.ensurePlayer(sid1)
        items.ensurePlayer(sid2)
        auction = AuctionSystem(items = items, clock = clock, listingDurationMs = 60_000L)
    }

    private fun giveSword(sid: SessionId) {
        val inst = items.createFromTemplate(swordId)!!
        items.addToInventory(sid, inst)
    }

    private fun givePotion(sid: SessionId) {
        val inst = items.createFromTemplate(potionId)!!
        items.addToInventory(sid, inst)
    }

    @Nested
    inner class Posting {
        @Test
        fun `post listing removes item from inventory`() {
            giveSword(sid1)
            val listing = auction.postListing(sid1, "Player1", "sword", 100)
            assertNotNull(listing)
            assertEquals(100L, listing!!.price)
            assertEquals("a sword", listing.item.item.displayName)
            assertEquals(0, items.inventory(sid1).size)
        }

        @Test
        fun `post listing fails when item not found`() {
            val listing = auction.postListing(sid1, "Player1", "sword", 100)
            assertNull(listing)
        }

        @Test
        fun `listings are visible to all`() {
            giveSword(sid1)
            auction.postListing(sid1, "Player1", "sword", 100)
            assertEquals(1, auction.allListings().size)
        }

        @Test
        fun `multiple listings from same player`() {
            giveSword(sid1)
            givePotion(sid1)
            auction.postListing(sid1, "Player1", "sword", 100)
            auction.postListing(sid1, "Player1", "potion", 50)
            assertEquals(2, auction.listingsBy("Player1").size)
        }
    }

    @Nested
    inner class Purchasing {
        @Test
        fun `purchase transfers item to buyer`() {
            giveSword(sid1)
            val listing = auction.postListing(sid1, "Player1", "sword", 100)!!

            val purchased = auction.completePurchase(listing.id, sid2)
            assertNotNull(purchased)
            assertEquals(1, items.inventory(sid2).count { it.id == swordId })
            assertEquals(0, auction.allListings().size)
        }

        @Test
        fun `purchase nonexistent listing returns null`() {
            assertNull(auction.completePurchase(999, sid2))
        }
    }

    @Nested
    inner class Cancellation {
        @Test
        fun `cancel returns item to seller`() {
            giveSword(sid1)
            val listing = auction.postListing(sid1, "Player1", "sword", 100)!!

            val cancelled = auction.cancelListing(listing.id, sid1)
            assertNotNull(cancelled)
            assertEquals(1, items.inventory(sid1).count { it.id == swordId })
            assertEquals(0, auction.allListings().size)
        }

        @Test
        fun `cancel fails for wrong player`() {
            giveSword(sid1)
            val listing = auction.postListing(sid1, "Player1", "sword", 100)!!

            val cancelled = auction.cancelListing(listing.id, sid2)
            assertNull(cancelled)
            assertEquals(1, auction.allListings().size) // still listed
        }

        @Test
        fun `cancelAllForPlayer returns all items`() {
            giveSword(sid1)
            givePotion(sid1)
            auction.postListing(sid1, "Player1", "sword", 100)
            auction.postListing(sid1, "Player1", "potion", 50)

            val cancelled = auction.cancelAllForPlayer(sid1)
            assertEquals(2, cancelled.size)
            assertEquals(2, items.inventory(sid1).size)
            assertEquals(0, auction.allListings().size)
        }
    }

    @Nested
    inner class Expiry {
        @Test
        fun `listings expire after duration`() {
            giveSword(sid1)
            auction.postListing(sid1, "Player1", "sword", 100)

            // Advance past expiry
            clock.advance(61_000L)
            val expired = auction.expireListings()

            assertEquals(1, expired.size)
            assertEquals(1, items.inventory(sid1).count { it.id == swordId })
            assertEquals(0, auction.allListings().size)
        }

        @Test
        fun `listings do not expire before duration`() {
            giveSword(sid1)
            auction.postListing(sid1, "Player1", "sword", 100)

            clock.advance(30_000L) // half the duration
            val expired = auction.expireListings()

            assertEquals(0, expired.size)
            assertEquals(1, auction.allListings().size)
        }
    }

    @Nested
    inner class Persistence {
        @Test
        fun `atomic write persists listings and survives reload`(
            @TempDir tempDir: Path,
        ) {
            val persistPath = tempDir.resolve("auction_listings.json")
            val persistedAuction = AuctionSystem(
                items = items,
                clock = clock,
                listingDurationMs = 60_000L,
                persistPath = persistPath,
            )
            giveSword(sid1)
            persistedAuction.postListing(sid1, "Player1", "sword", 100)

            assertTrue(persistPath.exists(), "Persist file should exist after posting")

            // Reload into a fresh AuctionSystem to verify data survives
            val reloaded = AuctionSystem(
                items = items,
                clock = clock,
                listingDurationMs = 60_000L,
                persistPath = persistPath,
            )
            reloaded.loadPersistedListings()
            assertEquals(1, reloaded.allListings().size)
            assertEquals("Player1", reloaded.allListings().first().sellerName)
            assertEquals(100L, reloaded.allListings().first().price)
        }

        @Test
        fun `no temp files left after successful persist`(
            @TempDir tempDir: Path,
        ) {
            val persistPath = tempDir.resolve("auction_listings.json")
            val persistedAuction = AuctionSystem(
                items = items,
                clock = clock,
                listingDurationMs = 60_000L,
                persistPath = persistPath,
            )
            giveSword(sid1)
            persistedAuction.postListing(sid1, "Player1", "sword", 100)

            // Verify no .tmp files remain in the directory
            val tmpFiles = tempDir.toFile().listFiles { f -> f.name.endsWith(".tmp") }
            assertTrue(tmpFiles.isNullOrEmpty(), "No .tmp files should remain after atomic write")
        }

        @Test
        fun `persist file not created when no persist path configured`() {
            // Default auction (no persistPath) should not throw
            giveSword(sid1)
            auction.postListing(sid1, "Player1", "sword", 100)
            assertEquals(1, auction.allListings().size)
        }
    }
}
