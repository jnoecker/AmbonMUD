package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.AuctionSystem
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.CreateResult
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.MutableClock
import dev.ambon.test.createTestPlayer
import dev.ambon.test.drainAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Integration coverage for the auction house command flow through [CommandRouter]
 * → [dev.ambon.engine.commands.handlers.AuctionHandler] → [AuctionSystem]. The
 * pure marketplace mechanics are unit-tested in
 * [dev.ambon.engine.AuctionSystemTest]; these tests exercise the handler glue:
 * inventory escrow, gold transfer to online vs. offline sellers, broadcast
 * messaging, and the guard rails (quest items, own listings, claimed accounts).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterAuctionTest {
    private val swordId = ItemId("auction:sword")

    @Test
    fun `list shows posted listings to a browser`() =
        runTest {
            val env = setup()
            giveSword(env.items, env.sellerSid)
            env.auction.postListing(env.sellerSid, "Seller", "sword", 100)
            env.outbound.drainAll()

            env.router.handle(env.buyerSid, Command.AuctionList(null))

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("a sword") && it.text.contains("100") },
                "Expected the listing in the auction output. got=$outs",
            )
        }

    @Test
    fun `list with no listings reports empty`() =
        runTest {
            val env = setup()

            env.router.handle(env.buyerSid, Command.AuctionList(null))

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("No auction listings") },
                "Expected an empty-listings message. got=$outs",
            )
        }

    @Test
    fun `sell escrows the item and announces the listing`() =
        runTest {
            val env = setup()
            giveSword(env.items, env.sellerSid)

            env.router.handle(env.sellerSid, Command.AuctionSell("sword", 250))

            // Item left the seller's inventory and is held by the auction house.
            assertTrue(env.items.inventory(env.sellerSid).isEmpty())
            assertEquals(1, env.auction.allListings().size)
            assertEquals(250L, env.auction.allListings().first().price)

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("Listed a sword") && it.text.contains("250 gold") },
                "Expected a listing confirmation to the seller. got=$outs",
            )
            // The buyer, who is also online, hears the broadcast.
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == env.buyerSid &&
                        it.text.contains("[Auction]") &&
                        it.text.contains("a sword")
                },
                "Expected a broadcast to other online players. got=$outs",
            )
        }

    @Test
    fun `sell rejects a quest item`() =
        runTest {
            val env = setup()
            env.items.addToInventory(
                env.sellerSid,
                ItemInstance(
                    ItemId("auction:relic"),
                    Item(keyword = "relic", displayName = "the sacred relic", questItem = true),
                ),
            )

            env.router.handle(env.sellerSid, Command.AuctionSell("relic", 100))

            assertEquals(1, env.items.inventory(env.sellerSid).size)
            assertTrue(env.auction.allListings().isEmpty())

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("quest item") },
                "Expected a quest-item rejection. got=$outs",
            )
        }

    @Test
    fun `sell rejects a non-positive price`() =
        runTest {
            val env = setup()
            giveSword(env.items, env.sellerSid)

            env.router.handle(env.sellerSid, Command.AuctionSell("sword", 0))

            assertEquals(1, env.items.inventory(env.sellerSid).size)
            assertTrue(env.auction.allListings().isEmpty())

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("greater than 0") },
                "Expected an invalid-price rejection. got=$outs",
            )
        }

    @Test
    fun `sell fails when the item is not carried`() =
        runTest {
            val env = setup()

            env.router.handle(env.sellerSid, Command.AuctionSell("sword", 100))

            assertTrue(env.auction.allListings().isEmpty())

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("aren't carrying") },
                "Expected a not-carried rejection. got=$outs",
            )
        }

    @Test
    fun `buy transfers the item and pays an online seller`() =
        runTest {
            val env = setup()
            giveSword(env.items, env.sellerSid)
            env.auction.postListing(env.sellerSid, "Seller", "sword", 100)
            env.seller.gold = 0L
            env.buyer.gold = 500L
            env.outbound.drainAll()
            val listingId = env.auction.allListings().first().id

            env.router.handle(env.buyerSid, Command.AuctionBuy(listingId))

            assertEquals(400L, env.buyer.gold, "Buyer should be charged the price")
            assertEquals(100L, env.seller.gold, "Online seller should be paid")
            assertEquals(1, env.items.inventory(env.buyerSid).count { it.id == swordId })
            assertTrue(env.auction.allListings().isEmpty(), "Listing should be consumed")

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("You purchased a sword") },
                "Expected a purchase confirmation to the buyer. got=$outs",
            )
            assertTrue(
                outs.any {
                    it is OutboundEvent.SendInfo &&
                        it.sessionId == env.sellerSid &&
                        it.text.contains("purchased your a sword")
                },
                "Expected a sale notification to the online seller. got=$outs",
            )
        }

    @Test
    fun `buy credits an offline seller via the repository`() =
        runTest {
            val env = setup()
            // An offline seller: a persisted record exists, but they are not logged in.
            val offlineSid = SessionId(99L)
            env.items.ensurePlayer(offlineSid)
            env.repo.createTestPlayer("Absent", env.world.startRoom)
            giveSword(env.items, offlineSid)
            env.auction.postListing(offlineSid, "Absent", "sword", 100)
            env.buyer.gold = 500L
            env.outbound.drainAll()
            val listingId = env.auction.allListings().first().id

            env.router.handle(env.buyerSid, Command.AuctionBuy(listingId))

            assertEquals(400L, env.buyer.gold)
            assertEquals(1, env.items.inventory(env.buyerSid).count { it.id == swordId })
            assertEquals(
                100L,
                env.repo.findByName("Absent")!!.gold,
                "Offline seller's gold should be credited in the repository",
            )
        }

    @Test
    fun `buy rejects buying your own listing`() =
        runTest {
            val env = setup()
            giveSword(env.items, env.sellerSid)
            env.auction.postListing(env.sellerSid, "Seller", "sword", 100)
            env.seller.gold = 500L
            env.outbound.drainAll()
            val listingId = env.auction.allListings().first().id

            env.router.handle(env.sellerSid, Command.AuctionBuy(listingId))

            assertEquals(500L, env.seller.gold, "Gold should be untouched")
            assertEquals(1, env.auction.allListings().size, "Listing should remain")

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("cannot buy your own listing") },
                "Expected an own-listing rejection. got=$outs",
            )
        }

    @Test
    fun `buy with insufficient gold fails without changing state`() =
        runTest {
            val env = setup()
            giveSword(env.items, env.sellerSid)
            env.auction.postListing(env.sellerSid, "Seller", "sword", 100)
            env.buyer.gold = 50L
            env.outbound.drainAll()
            val listingId = env.auction.allListings().first().id

            env.router.handle(env.buyerSid, Command.AuctionBuy(listingId))

            assertEquals(50L, env.buyer.gold)
            assertTrue(env.items.inventory(env.buyerSid).isEmpty())
            assertEquals(1, env.auction.allListings().size, "Listing should remain available")

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("need 100 gold") },
                "Expected an insufficient-gold rejection. got=$outs",
            )
        }

    @Test
    fun `buy is rejected for an unclaimed demo character without changing state`() =
        runTest {
            val env = setup()
            giveSword(env.items, env.sellerSid)
            env.auction.postListing(env.sellerSid, "Seller", "sword", 100)
            val demoSid = SessionId(3L)
            env.items.ensurePlayer(demoSid)
            require(env.players.createDemo(demoSid, "Demobuyer") == CreateResult.Ok)
            val demo = env.players.get(demoSid)!!
            demo.gold = 500L
            env.outbound.drainAll()
            val listingId = env.auction.allListings().first().id

            env.router.handle(demoSid, Command.AuctionBuy(listingId))

            assertEquals(500L, demo.gold, "Demo buyer's gold should be untouched")
            assertTrue(env.items.inventory(demoSid).isEmpty(), "No item should transfer")
            assertEquals(1, env.auction.allListings().size, "Listing should remain available")

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("demo characters") },
                "Expected a demo-character rejection. got=$outs",
            )
        }

    @Test
    fun `buy a nonexistent listing fails`() =
        runTest {
            val env = setup()

            env.router.handle(env.buyerSid, Command.AuctionBuy(404))

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("not found") },
                "Expected a not-found rejection. got=$outs",
            )
        }

    @Test
    fun `cancel returns the item to the seller`() =
        runTest {
            val env = setup()
            giveSword(env.items, env.sellerSid)
            env.auction.postListing(env.sellerSid, "Seller", "sword", 100)
            env.outbound.drainAll()
            val listingId = env.auction.allListings().first().id

            env.router.handle(env.sellerSid, Command.AuctionCancel(listingId))

            assertTrue(env.auction.allListings().isEmpty())
            assertEquals(1, env.items.inventory(env.sellerSid).count { it.id == swordId })

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("Cancelled listing") },
                "Expected a cancellation confirmation. got=$outs",
            )
        }

    @Test
    fun `cancel someone elses listing fails`() =
        runTest {
            val env = setup()
            giveSword(env.items, env.sellerSid)
            env.auction.postListing(env.sellerSid, "Seller", "sword", 100)
            env.outbound.drainAll()
            val listingId = env.auction.allListings().first().id

            env.router.handle(env.buyerSid, Command.AuctionCancel(listingId))

            assertEquals(1, env.auction.allListings().size, "Listing should remain")
            assertNull(env.items.inventory(env.buyerSid).firstOrNull())

            val outs = env.outbound.drainAll()
            assertTrue(
                outs.any { it is OutboundEvent.SendError && it.text.contains("not yours") },
                "Expected a cancel-denied rejection. got=$outs",
            )
        }

    private fun giveSword(items: ItemRegistry, sid: SessionId) {
        items.addToInventory(sid, ItemInstance(swordId, Item(keyword = "sword", displayName = "a sword")))
    }

    private data class TestEnv(
        val world: dev.ambon.domain.world.World,
        val repo: InMemoryPlayerRepository,
        val items: ItemRegistry,
        val players: PlayerRegistry,
        val outbound: LocalOutboundBus,
        val auction: AuctionSystem,
        val router: CommandRouter,
        val sellerSid: SessionId,
        val buyerSid: SessionId,
        val seller: dev.ambon.engine.PlayerState,
        val buyer: dev.ambon.engine.PlayerState,
    )

    private suspend fun setup(): TestEnv {
        val world = WorldLoader.loadFromResource("world/ok_shop.yaml")
        val items = ItemRegistry()
        items.loadSpawns(world.itemSpawns)
        val repo = InMemoryPlayerRepository()
        val players = dev.ambon.test.buildTestPlayerRegistry(world.startRoom, repo, items)
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val clock = MutableClock(1000L)
        val auction = AuctionSystem(items = items, clock = clock, listingDurationMs = 60_000L)
        val combat = CombatSystem(players, mobs, items, outbound)
        val router = buildTestRouter(
            world = world,
            players = players,
            mobs = mobs,
            items = items,
            combat = combat,
            outbound = outbound,
            clock = clock,
            auctionSystem = auction,
            playerRepo = repo,
        )

        val sellerSid = SessionId(1L)
        val buyerSid = SessionId(2L)
        require(players.login(sellerSid, "Seller", "password") == LoginResult.Ok)
        require(players.login(buyerSid, "Buyer", "password") == LoginResult.Ok)
        outbound.drainAll()

        return TestEnv(
            world = world,
            repo = repo,
            items = items,
            players = players,
            outbound = outbound,
            auction = auction,
            router = router,
            sellerSid = sellerSid,
            buyerSid = buyerSid,
            seller = players.get(sellerSid)!!,
            buyer = players.get(buyerSid)!!,
        )
    }
}
