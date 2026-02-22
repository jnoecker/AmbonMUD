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
import org.junit.jupiter.api.Assertions.assertFalse
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
    private val players =
        PlayerRegistry(
            startRoom = startRoom,
            repo = repo,
            items = items,
            clock = clock,
            progression = PlayerProgression(),
        )

    private val outboundChannel = Channel<OutboundEvent>(100)
    private val outbound =
        object : OutboundBus {
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

    private val zoneRegistry =
        StaticZoneRegistry(
            mapOf(
                sourceEngineId to
                    Pair(
                        EngineAddress(sourceEngineId, "host1", 9090),
                        setOf("forest"),
                    ),
                targetEngineId to
                    Pair(
                        EngineAddress(targetEngineId, "host2", 9091),
                        setOf("swamp"),
                    ),
            ),
        )

    private val handoffManager =
        HandoffManager(
            engineId = sourceEngineId,
            players = players,
            items = items,
            outbound = outbound,
            bus = bus,
            zoneRegistry = zoneRegistry,
            isTargetRoomLocal = { room -> room.zone == "forest" },
            clock = clock,
            ackTimeoutMs = 1_000L,
        )

    private val sid = SessionId(1L)

    @BeforeEach
    fun setup() =
        runBlocking {
            bus.start()
        }

    @Test
    fun `serializePlayer captures full player state`() {
        val player =
            PlayerState(
                sessionId = sid,
                name = "Alice",
                roomId = RoomId("forest:clearing"),
                playerId = PlayerId(42L),
                hp = 15,
                maxHp = 20,
                baseMaxHp = 18,
                constitution = 3,
                level = 5,
                xpTotal = 25_000L,
                ansiEnabled = true,
                isStaff = false,
            )

        val sword =
            ItemInstance(
                id = ItemId("forest:sword"),
                item = Item(keyword = "sword", displayName = "a sharp sword", slot = ItemSlot.HAND, damage = 5),
            )
        val shield =
            ItemInstance(
                id = ItemId("forest:shield"),
                item = Item(keyword = "shield", displayName = "a wooden shield", slot = ItemSlot.BODY, armor = 2),
            )
        val coin =
            ItemInstance(
                id = ItemId("forest:coin"),
                item = Item(keyword = "coin", displayName = "a gold coin"),
            )

        val serialized =
            HandoffManager.serializePlayer(
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
        assertEquals(25_000L, serialized.xpTotal)
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
        val original =
            ItemInstance(
                id = ItemId("zone:magic_staff"),
                item =
                    Item(
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
    fun `initiateHandoff returns PlayerNotFound for unknown session`() =
        runBlocking {
            val result = handoffManager.initiateHandoff(SessionId(999L), targetRoom)
            assertEquals(HandoffResult.PlayerNotFound, result)
        }

    @Test
    fun `initiateHandoff returns NoEngineForZone when zone has no owner`() =
        runBlocking {
            players.create(sid, "Alice", "password123")

            val result = handoffManager.initiateHandoff(sid, RoomId("unknown_zone:room1"))
            assertEquals(HandoffResult.NoEngineForZone, result)
            assertNotNull(players.get(sid))
            assertFalse(handoffManager.isInTransit(sid))
        }

    @Test
    fun `initiateHandoff keeps player local until ack and marks transit`() =
        runBlocking {
            players.create(sid, "Alice", "password123")

            val result = handoffManager.initiateHandoff(sid, targetRoom)
            assertTrue(result is HandoffResult.Initiated)
            assertTrue(handoffManager.isInTransit(sid))
            assertNotNull(players.get(sid))

            val events = drainOutbound()
            assertTrue(events.any { it is OutboundEvent.SendText && "shimmers" in it.text })
            assertTrue(events.none { it is OutboundEvent.SessionRedirect })

            val handoffMessage = bus.incoming().tryReceive().getOrNull() as? InterEngineMessage.PlayerHandoff
            assertNotNull(handoffMessage)
            assertEquals(sid.value, handoffMessage!!.sessionId)
            assertEquals(targetRoom.value, handoffMessage.targetRoomId)
        }

    @Test
    fun `initiateHandoff returns AlreadyInTransit when pending`() =
        runBlocking {
            players.create(sid, "Alice", "password123")
            val first = handoffManager.initiateHandoff(sid, targetRoom)
            val second = handoffManager.initiateHandoff(sid, targetRoom)

            assertTrue(first is HandoffResult.Initiated)
            assertEquals(HandoffResult.AlreadyInTransit, second)
        }

    @Test
    fun `handleAck success removes player and emits redirect`() =
        runBlocking {
            players.create(sid, "Alice", "password123")
            handoffManager.initiateHandoff(sid, targetRoom)
            drainOutbound()
            bus.incoming().tryReceive() // consume PlayerHandoff message

            val result =
                handoffManager.handleAck(
                    InterEngineMessage.HandoffAck(
                        sessionId = sid.value,
                        success = true,
                    ),
                )

            assertTrue(result is HandoffAckResult.Completed)
            assertFalse(handoffManager.isInTransit(sid))
            assertNull(players.get(sid))

            val redirect = drainOutbound().filterIsInstance<OutboundEvent.SessionRedirect>().single()
            assertEquals(sid, redirect.sessionId)
            assertEquals(targetEngineId, redirect.newEngineId)
            assertEquals("host2", redirect.newEngineHost)
            assertEquals(9091, redirect.newEnginePort)
        }

    @Test
    fun `handleAck failure keeps player local and clears transit`() =
        runBlocking {
            players.create(sid, "Alice", "password123")
            handoffManager.initiateHandoff(sid, targetRoom)

            val result =
                handoffManager.handleAck(
                    InterEngineMessage.HandoffAck(
                        sessionId = sid.value,
                        success = false,
                        errorMessage = "target rejected",
                    ),
                )

            assertEquals(HandoffAckResult.Failed("target rejected"), result)
            assertFalse(handoffManager.isInTransit(sid))
            assertNotNull(players.get(sid))
            assertTrue(drainOutbound().none { it is OutboundEvent.SessionRedirect })
        }

    @Test
    fun `expireTimedOut returns stale handoff`() =
        runBlocking {
            players.create(sid, "Alice", "password123")
            handoffManager.initiateHandoff(sid, targetRoom)
            assertTrue(handoffManager.isInTransit(sid))

            clock.advance(1_001L)
            val expired = handoffManager.expireTimedOut()

            assertEquals(1, expired.size)
            assertEquals(sid, expired.single().sessionId)
            assertFalse(handoffManager.isInTransit(sid))
            assertNotNull(players.get(sid))
        }

    @Test
    fun `acceptHandoff creates player in local registries and sends success ack`() =
        runBlocking {
            val targetManager =
                HandoffManager(
                    engineId = targetEngineId,
                    players = players,
                    items = items,
                    outbound = outbound,
                    bus = bus,
                    zoneRegistry = zoneRegistry,
                    isTargetRoomLocal = { room -> room.zone == "swamp" },
                    clock = clock,
                )

            val handoff =
                InterEngineMessage.PlayerHandoff(
                    sessionId = 42L,
                    targetRoomId = "swamp:edge",
                    playerState =
                        SerializedPlayerState(
                            playerId = 10L,
                            name = "Bob",
                            roomId = "forest:clearing",
                            hp = 18,
                            maxHp = 25,
                            baseMaxHp = 22,
                            constitution = 4,
                            level = 7,
                            xpTotal = 40_000L,
                            ansiEnabled = true,
                            isStaff = false,
                            passwordHash = "hash123",
                            createdEpochMs = 1_000L,
                            lastSeenEpochMs = 2_000L,
                            inventoryItems =
                                listOf(
                                    SerializedItem(
                                        id = "forest:coin",
                                        keyword = "coin",
                                        displayName = "a gold coin",
                                    ),
                                ),
                            equippedItems =
                                mapOf(
                                    "HAND" to
                                        SerializedItem(
                                            id = "forest:sword",
                                            keyword = "sword",
                                            displayName = "a sharp sword",
                                            slot = "HAND",
                                            damage = 5,
                                        ),
                                ),
                        ),
                    gatewayId = 1,
                    sourceEngineId = sourceEngineId,
                )

            val resultSid = targetManager.acceptHandoff(handoff)
            assertEquals(SessionId(42L), resultSid)

            val player = players.get(SessionId(42L))
            assertNotNull(player)
            assertEquals("Bob", player!!.name)
            assertEquals(RoomId("swamp:edge"), player.roomId)
            assertEquals(18, player.hp)
            assertEquals(25, player.maxHp)
            assertEquals(7, player.level)
            assertEquals(40_000L, player.xpTotal)
            assertTrue(player.ansiEnabled)

            val inv = items.inventory(SessionId(42L))
            assertEquals(1, inv.size)
            assertEquals("forest:coin", inv[0].id.value)

            val eq = items.equipment(SessionId(42L))
            assertEquals("forest:sword", eq[ItemSlot.HAND]!!.id.value)

            val ack = bus.incoming().tryReceive().getOrNull() as? InterEngineMessage.HandoffAck
            assertNotNull(ack)
            assertTrue(ack!!.success)
        }

    @Test
    fun `acceptHandoff rejects non-local target room and sends failure ack`() =
        runBlocking {
            val targetManager =
                HandoffManager(
                    engineId = targetEngineId,
                    players = players,
                    items = items,
                    outbound = outbound,
                    bus = bus,
                    zoneRegistry = zoneRegistry,
                    isTargetRoomLocal = { false },
                    clock = clock,
                )

            val handoff =
                InterEngineMessage.PlayerHandoff(
                    sessionId = 42L,
                    targetRoomId = "swamp:edge",
                    playerState =
                        SerializedPlayerState(
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
                    sourceEngineId = sourceEngineId,
                )

            val result = targetManager.acceptHandoff(handoff)
            assertNull(result)

            val ack = bus.incoming().tryReceive().getOrNull() as? InterEngineMessage.HandoffAck
            assertNotNull(ack)
            assertFalse(ack!!.success)
        }

    private fun drainOutbound(): List<OutboundEvent> {
        val events = mutableListOf<OutboundEvent>()
        while (true) {
            val ev = outboundChannel.tryReceive().getOrNull() ?: break
            events += ev
        }
        return events
    }
}
