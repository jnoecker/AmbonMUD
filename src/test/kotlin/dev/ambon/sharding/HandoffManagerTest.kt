package dev.ambon.sharding

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.items.ItemSlot
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.persistence.PlayerId
import dev.ambon.test.MutableClock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HandoffManagerTest {
    private val clock = MutableClock(0L)
    private val repo = InMemoryPlayerRepository()
    private val items = ItemRegistry()
    private val startRoom = RoomId("forest:clearing")
    private val players = PlayerRegistry(
        startRoom = startRoom,
        repo = repo,
        items = items,
        clock = clock,
        progression = PlayerProgression(),
    )

    private val outboundChannel = Channel<OutboundEvent>(100)
    private val outbound = object : OutboundBus {
        override suspend fun send(event: OutboundEvent) {
            outboundChannel.send(event)
        }

        override fun asReceiveChannel() = outboundChannel

        override fun tryReceive() = outboundChannel.tryReceive()

        override fun close() {
            outboundChannel.close()
        }
    }

    private val bus = LocalInterEngineBus()
    private val sourceEngineId = "engine-1"
    private val targetEngineId = "engine-2"
    private val targetRoom = RoomId("swamp:edge")

    private val zoneRegistry = StaticZoneRegistry(
        mapOf(
            sourceEngineId to Pair(
                EngineAddress(sourceEngineId, "host1", 9090),
                setOf("forest"),
            ),
            targetEngineId to Pair(
                EngineAddress(targetEngineId, "host2", 9091),
                setOf("swamp"),
            ),
        ),
    )

    private val handoffManager = HandoffManager(
        engineId = sourceEngineId,
        players = players,
        items = items,
        outbound = outbound,
        bus = bus,
        zoneRegistry = zoneRegistry,
        clock = clock,
    )

    private val sid = SessionId(1L)

    @BeforeEach
    fun setup() = runBlocking {
        bus.start()
    }

    @Test
    fun `serializePlayer captures full player state`() {
        val player = PlayerState(
            sessionId = sid,
            name = "Alice",
            roomId = RoomId("forest:clearing"),
            playerId = PlayerId(42L),
            hp = 15,
            maxHp = 20,
            baseMaxHp = 18,
            constitution = 3,
            level = 5,
            xpTotal = 25000L,
            ansiEnabled = true,
            isStaff = false,
        )

        val sword = ItemInstance(
            id = ItemId("forest:sword"),
            item = Item(keyword = "sword", displayName = "a sharp sword", slot = ItemSlot.HAND, damage = 5),
        )
        val shield = ItemInstance(
            id = ItemId("forest:shield"),
            item = Item(keyword = "shield", displayName = "a wooden shield", slot = ItemSlot.BODY, armor = 2),
        )
        val coin = ItemInstance(
            id = ItemId("forest:coin"),
            item = Item(keyword = "coin", displayName = "a gold coin"),
        )

        val serialized = HandoffManager.serializePlayer(
            player = player,
            targetRoomId = targetRoom,
            inventory = listOf(coin),
            equipment = mapOf(ItemSlot.HAND to sword, ItemSlot.BODY to shield),
        )

        assertEquals(42L, serialized.playerId)
        assertEquals("Alice", serialized.name)
        assertEquals(targetRoom.value, serialized.roomId)
        assertEquals(15, serialized.hp)
        assertEquals(20, serialized.maxHp)
        assertEquals(18, serialized.baseMaxHp)
        assertEquals(3, serialized.constitution)
        assertEquals(5, serialized.level)
        assertEquals(25000L, serialized.xpTotal)
        assertTrue(serialized.ansiEnabled)

        assertEquals(1, serialized.inventoryItems.size)
        assertEquals("forest:coin", serialized.inventoryItems[0].id)
        assertEquals("coin", serialized.inventoryItems[0].keyword)

        assertEquals(2, serialized.equippedItems.size)
        assertEquals("forest:sword", serialized.equippedItems["HAND"]?.id)
        assertEquals(5, serialized.equippedItems["HAND"]?.damage)
        assertEquals("forest:shield", serialized.equippedItems["BODY"]?.id)
        assertEquals(2, serialized.equippedItems["BODY"]?.armor)
    }

    @Test
    fun `serializeItem and deserializeItem round-trip`() {
        val original = ItemInstance(
            id = ItemId("zone:magic_staff"),
            item = Item(
                keyword = "staff",
                displayName = "a glowing staff",
                description = "It hums with power.",
                slot = ItemSlot.HAND,
                damage = 7,
                armor = 1,
                constitution = 2,
                matchByKey = true,
            ),
        )

        val serialized = HandoffManager.serializeItem(original)
        val restored = HandoffManager.deserializeItem(serialized)

        assertEquals(original.id, restored.id)
        assertEquals(original.item.keyword, restored.item.keyword)
        assertEquals(original.item.displayName, restored.item.displayName)
        assertEquals(original.item.description, restored.item.description)
        assertEquals(original.item.slot, restored.item.slot)
        assertEquals(original.item.damage, restored.item.damage)
        assertEquals(original.item.armor, restored.item.armor)
        assertEquals(original.item.constitution, restored.item.constitution)
        assertEquals(original.item.matchByKey, restored.item.matchByKey)
    }

    @Test
    fun `initiateHandoff returns PlayerNotFound for unknown session`() = runBlocking {
        val result = handoffManager.initiateHandoff(SessionId(999L), targetRoom)
        assertEquals(HandoffResult.PlayerNotFound, result)
    }

    @Test
    fun `initiateHandoff returns NoEngineForZone when zone has no owner`() = runBlocking {
        // Create a player via PlayerRegistry (handles BCrypt hashing properly)
        players.create(sid, "Alice", "password123")

        val result = handoffManager.initiateHandoff(sid, RoomId("unknown_zone:room1"))
        assertEquals(HandoffResult.NoEngineForZone, result)

        // Player should still exist (handoff was not initiated)
        assertNotNull(players.get(sid))
    }

    @Test
    fun `initiateHandoff removes player and sends handoff message`() = runBlocking {
        // Create and login a player via PlayerRegistry
        players.create(sid, "Alice", "password123")

        // Give the player an inventory item
        val coin = ItemInstance(
            id = ItemId("forest:coin"),
            item = Item(keyword = "coin", displayName = "a gold coin"),
        )
        items.addToInventory(sid, coin)

        val result = handoffManager.initiateHandoff(sid, targetRoom)

        assertTrue(result is HandoffResult.Initiated)
        assertEquals(targetEngineId, (result as HandoffResult.Initiated).targetEngine.engineId)

        // Player should be removed from local registry
        assertNull(players.get(sid))

        // Verify outbound messages were sent (shimmer message + departure)
        // Drain a few events to verify
        val events = mutableListOf<OutboundEvent>()
        while (true) {
            val ev = outboundChannel.tryReceive().getOrNull() ?: break
            events.add(ev)
        }
        assertTrue(events.any { it is OutboundEvent.SendText && "shimmers" in (it as OutboundEvent.SendText).text })

        // Verify a SessionRedirect was sent before the shimmer message
        val redirectEvent = events.filterIsInstance<OutboundEvent.SessionRedirect>().firstOrNull()
        assertNotNull(redirectEvent)
        assertEquals(sid, redirectEvent!!.sessionId)
        assertEquals(targetEngineId, redirectEvent.newEngineId)
        assertEquals("host2", redirectEvent.newEngineHost)
        assertEquals(9091, redirectEvent.newEnginePort)
    }

    @Test
    fun `initiateHandoff sends SessionRedirect before text message`() = runBlocking {
        players.create(sid, "Alice", "password123")

        handoffManager.initiateHandoff(sid, targetRoom)

        val events = mutableListOf<OutboundEvent>()
        while (true) {
            val ev = outboundChannel.tryReceive().getOrNull() ?: break
            events.add(ev)
        }

        // SessionRedirect should come before the shimmer text
        val redirectIdx = events.indexOfFirst { it is OutboundEvent.SessionRedirect }
        val textIdx = events.indexOfFirst { it is OutboundEvent.SendText && "shimmers" in (it as OutboundEvent.SendText).text }
        assertTrue(redirectIdx >= 0, "Should have a SessionRedirect event")
        assertTrue(textIdx >= 0, "Should have a shimmer text event")
        assertTrue(redirectIdx < textIdx, "SessionRedirect should come before the shimmer text")
    }

    @Test
    fun `acceptHandoff creates player in local registries with items`() = runBlocking {
        val handoff = InterEngineMessage.PlayerHandoff(
            sessionId = 42L,
            targetRoomId = "swamp:edge",
            playerState = SerializedPlayerState(
                playerId = 10L,
                name = "Bob",
                roomId = "forest:clearing",
                hp = 18,
                maxHp = 25,
                baseMaxHp = 22,
                constitution = 4,
                level = 7,
                xpTotal = 40000L,
                ansiEnabled = true,
                isStaff = false,
                passwordHash = "hash123",
                createdEpochMs = 1000L,
                lastSeenEpochMs = 2000L,
                inventoryItems = listOf(
                    SerializedItem(
                        id = "forest:coin",
                        keyword = "coin",
                        displayName = "a gold coin",
                    ),
                ),
                equippedItems = mapOf(
                    "HAND" to SerializedItem(
                        id = "forest:sword",
                        keyword = "sword",
                        displayName = "a sharp sword",
                        slot = "HAND",
                        damage = 5,
                    ),
                ),
            ),
            gatewayId = 1,
        )

        val resultSid = handoffManager.acceptHandoff(handoff)

        assertNotNull(resultSid)
        assertEquals(SessionId(42L), resultSid)

        // Verify player was added to registry
        val player = players.get(SessionId(42L))
        assertNotNull(player)
        assertEquals("Bob", player!!.name)
        assertEquals(RoomId("swamp:edge"), player.roomId)
        assertEquals(18, player.hp)
        assertEquals(25, player.maxHp)
        assertEquals(7, player.level)
        assertEquals(40000L, player.xpTotal)
        assertTrue(player.ansiEnabled)

        // Verify inventory was restored
        val inv = items.inventory(SessionId(42L))
        assertEquals(1, inv.size)
        assertEquals("forest:coin", inv[0].id.value)
        assertEquals("a gold coin", inv[0].item.displayName)

        // Verify equipment was restored
        val eq = items.equipment(SessionId(42L))
        assertEquals(1, eq.size)
        assertNotNull(eq[ItemSlot.HAND])
        assertEquals("forest:sword", eq[ItemSlot.HAND]!!.id.value)
        assertEquals(5, eq[ItemSlot.HAND]!!.item.damage)
    }

    @Test
    fun `acceptHandoff rejects duplicate session`() = runBlocking {
        // First accept
        val handoff = InterEngineMessage.PlayerHandoff(
            sessionId = 42L,
            targetRoomId = "swamp:edge",
            playerState = SerializedPlayerState(
                playerId = 10L,
                name = "Bob",
                roomId = "forest:clearing",
                hp = 20,
                maxHp = 20,
                baseMaxHp = 20,
                constitution = 0,
                level = 1,
                xpTotal = 0L,
                ansiEnabled = false,
                isStaff = false,
                passwordHash = "",
                createdEpochMs = 0L,
                lastSeenEpochMs = 0L,
            ),
            gatewayId = 1,
        )

        val first = handoffManager.acceptHandoff(handoff)
        assertNotNull(first)

        // Second accept with same session ID should be rejected
        val second = handoffManager.acceptHandoff(handoff)
        assertNull(second)
    }
}
