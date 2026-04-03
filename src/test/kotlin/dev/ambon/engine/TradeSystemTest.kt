package dev.ambon.engine

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.ItemSpawn
import dev.ambon.engine.items.ItemRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TradeSystemTest {
    private val sid1 = SessionId(1L)
    private val sid2 = SessionId(2L)
    private val sid3 = SessionId(3L)
    private val swordId = ItemId("test:sword")
    private val potionId = ItemId("test:potion")

    private lateinit var items: ItemRegistry
    private lateinit var trade: TradeSystem

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
        trade = TradeSystem(items)
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
    inner class SessionManagement {
        @Test
        fun `create session tracks both players`() {
            val session = trade.createSession(sid1, sid2)
            assertNotNull(session)
            assertTrue(trade.isInTrade(sid1))
            assertTrue(trade.isInTrade(sid2))
            assertFalse(trade.isInTrade(sid3))
        }

        @Test
        fun `cannot create session when initiator already in trade`() {
            trade.createSession(sid1, sid2)
            assertNull(trade.createSession(sid1, sid3))
        }

        @Test
        fun `cannot create session when target already in trade`() {
            trade.createSession(sid1, sid2)
            assertNull(trade.createSession(sid3, sid2))
        }

        @Test
        fun `cancel returns items to owners`() {
            giveSword(sid1)
            givePotion(sid2)
            val session = trade.createSession(sid1, sid2)!!

            trade.offerItem(sid1, "sword")
            trade.offerItem(sid2, "potion")

            // Items should be in escrow (removed from inventory)
            assertEquals(0, items.inventory(sid1).size)
            assertEquals(0, items.inventory(sid2).size)

            trade.cancel(session)

            // Items returned
            assertEquals(1, items.inventory(sid1).count { it.id == swordId })
            assertEquals(1, items.inventory(sid2).count { it.id == potionId })
            assertFalse(trade.isInTrade(sid1))
            assertFalse(trade.isInTrade(sid2))
        }

        @Test
        fun `cancelForPlayer cancels and returns items`() {
            giveSword(sid1)
            trade.createSession(sid1, sid2)!!
            trade.offerItem(sid1, "sword")

            val cancelled = trade.cancelForPlayer(sid1)
            assertNotNull(cancelled)
            assertEquals(1, items.inventory(sid1).count { it.id == swordId })
            assertFalse(trade.isInTrade(sid1))
            assertFalse(trade.isInTrade(sid2))
        }
    }

    @Nested
    inner class ItemOffers {
        @Test
        fun `offer item moves it from inventory to escrow`() {
            giveSword(sid1)
            trade.createSession(sid1, sid2)!!

            val result = trade.offerItem(sid1, "sword")
            assertNotNull(result)
            assertEquals(0, items.inventory(sid1).size) // removed from inventory
            assertEquals(swordId, result!!.second.id)
        }

        @Test
        fun `offer item not in inventory returns null`() {
            trade.createSession(sid1, sid2)!!
            val result = trade.offerItem(sid1, "sword")
            assertNull(result)
        }

        @Test
        fun `offer resets acceptance`() {
            giveSword(sid1)
            val session = trade.createSession(sid1, sid2)!!
            trade.accept(sid1)
            assertTrue(session.initiatorAccepted)

            trade.offerItem(sid1, "sword")
            assertFalse(session.initiatorAccepted) // reset
        }
    }

    @Nested
    inner class GoldOffers {
        @Test
        fun `offer gold sets amount`() {
            trade.createSession(sid1, sid2)!!
            val (session, error) = trade.offerGold(sid1, 100L, 500L)
            assertNotNull(session)
            assertNull(error)
            assertEquals(100L, session!!.initiatorGold)
        }

        @Test
        fun `offer gold fails with insufficient funds`() {
            trade.createSession(sid1, sid2)!!
            val (session, error) = trade.offerGold(sid1, 1000L, 500L)
            assertNull(session)
            assertTrue(error is TradeError.InsufficientGold)
        }

        @Test
        fun `offer gold resets acceptance`() {
            val session = trade.createSession(sid1, sid2)!!
            trade.accept(sid1)
            assertTrue(session.initiatorAccepted)

            trade.offerGold(sid1, 50L, 100L)
            assertFalse(session.initiatorAccepted)
        }
    }

    @Nested
    inner class Acceptance {
        @Test
        fun `accept marks side as accepted`() {
            val session = trade.createSession(sid1, sid2)!!
            trade.accept(sid1)
            assertTrue(session.initiatorAccepted)
            assertFalse(session.targetAccepted)
            assertFalse(session.bothAccepted())
        }

        @Test
        fun `both accept marks trade as ready`() {
            val session = trade.createSession(sid1, sid2)!!
            trade.accept(sid1)
            trade.accept(sid2)
            assertTrue(session.bothAccepted())
        }
    }

    @Nested
    inner class Completion {
        private var initGold = 0L
        private var targetGold = 0L

        private fun completeWith(
            session: TradeSession,
            iGold: Long = 500L,
            tGold: Long = 500L,
        ): TradeResult {
            initGold = iGold
            targetGold = tGold
            return trade.complete(
                session = session,
                initiatorGold = initGold,
                targetGold = targetGold,
                deductInitiatorGold = { delta -> initGold = (initGold + delta).coerceAtLeast(0) },
                deductTargetGold = { delta -> targetGold = (targetGold + delta).coerceAtLeast(0) },
            )
        }

        @Test
        fun `complete transfers items between players`() {
            giveSword(sid1)
            givePotion(sid2)
            val session = trade.createSession(sid1, sid2)!!

            trade.offerItem(sid1, "sword")
            trade.offerItem(sid2, "potion")
            trade.accept(sid1)
            trade.accept(sid2)

            val result = completeWith(session)

            assertEquals(TradeResult.Success, result)
            // sword went to sid2, potion went to sid1
            assertEquals(1, items.inventory(sid1).count { it.id == potionId })
            assertEquals(1, items.inventory(sid2).count { it.id == swordId })
            assertFalse(trade.isInTrade(sid1))
            assertFalse(trade.isInTrade(sid2))
        }

        @Test
        fun `complete with only one side offering items`() {
            giveSword(sid1)
            val session = trade.createSession(sid1, sid2)!!

            trade.offerItem(sid1, "sword")
            trade.accept(sid1)
            trade.accept(sid2)

            val result = completeWith(session)

            assertEquals(TradeResult.Success, result)
            // sword went to sid2, sid1 gets nothing (gift trade)
            assertEquals(0, items.inventory(sid1).size)
            assertEquals(1, items.inventory(sid2).count { it.id == swordId })
        }

        @Test
        fun `complete transfers gold between players`() {
            val session = trade.createSession(sid1, sid2)!!
            trade.offerGold(sid1, 100L, 500L)
            trade.offerGold(sid2, 50L, 500L)
            trade.accept(sid1)
            trade.accept(sid2)

            val result = completeWith(session, iGold = 500L, tGold = 500L)

            assertEquals(TradeResult.Success, result)
            // initiator: 500 - 100 + 50 = 450
            assertEquals(450L, initGold)
            // target: 500 - 50 + 100 = 550
            assertEquals(550L, targetGold)
        }

        @Test
        fun `complete re-validates initiator gold and fails if insufficient`() {
            giveSword(sid1)
            val session = trade.createSession(sid1, sid2)!!
            trade.offerGold(sid1, 200L, 500L) // offered 200 when they had 500
            trade.accept(sid1)
            trade.accept(sid2)

            // At completion time, initiator only has 50 gold
            val result = completeWith(session, iGold = 50L, tGold = 500L)

            assertTrue(result is TradeResult.InsufficientInitiatorGold)
            val err = result as TradeResult.InsufficientInitiatorGold
            assertEquals(50L, err.have)
            assertEquals(200L, err.need)
        }

        @Test
        fun `complete re-validates target gold and fails if insufficient`() {
            val session = trade.createSession(sid1, sid2)!!
            trade.offerGold(sid2, 300L, 500L) // offered 300 when they had 500
            trade.accept(sid1)
            trade.accept(sid2)

            // At completion time, target only has 100 gold
            val result = completeWith(session, iGold = 500L, tGold = 100L)

            assertTrue(result is TradeResult.InsufficientTargetGold)
            val err = result as TradeResult.InsufficientTargetGold
            assertEquals(100L, err.have)
            assertEquals(300L, err.need)
        }

        @Test
        fun `gold never goes negative via coerceAtLeast`() {
            val session = trade.createSession(sid1, sid2)!!
            trade.offerGold(sid1, 100L, 100L) // all their gold
            trade.accept(sid1)
            trade.accept(sid2)

            val result = completeWith(session, iGold = 100L, tGold = 0L)

            assertEquals(TradeResult.Success, result)
            // initiator: 100 - 100 + 0 = 0 (not negative)
            assertEquals(0L, initGold)
            // target: 0 - 0 + 100 = 100
            assertEquals(100L, targetGold)
        }

        @Test
        fun `zero gold offers succeed without validation issues`() {
            giveSword(sid1)
            val session = trade.createSession(sid1, sid2)!!
            trade.offerItem(sid1, "sword")
            // No gold offered
            trade.accept(sid1)
            trade.accept(sid2)

            val result = completeWith(session, iGold = 0L, tGold = 0L)

            assertEquals(TradeResult.Success, result)
            assertEquals(0L, initGold)
            assertEquals(0L, targetGold)
        }
    }
}
