package dev.ambon.sharding

import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.redis.redisObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class InterEngineMessageSerializationTest {
    private val mapper = redisObjectMapper

    @Test
    fun `GlobalBroadcast round-trips through JSON`() {
        val msg = InterEngineMessage.GlobalBroadcast(BroadcastType.GOSSIP, "Alice", "Hello!", sourceEngineId = "engine-1")
        val json = mapper.writeValueAsString(msg as InterEngineMessage)
        val deserialized = mapper.readValue<InterEngineMessage>(json)
        assertEquals(msg, deserialized)
    }

    @Test
    fun `TellMessage round-trips through JSON`() {
        val msg = InterEngineMessage.TellMessage("Alice", "Bob", "Hi there")
        val json = mapper.writeValueAsString(msg as InterEngineMessage)
        val deserialized = mapper.readValue<InterEngineMessage>(json)
        assertEquals(msg, deserialized)
    }

    @Test
    fun `WhoRequest round-trips through JSON`() {
        val msg = InterEngineMessage.WhoRequest("req-123", "engine-1")
        val json = mapper.writeValueAsString(msg as InterEngineMessage)
        val deserialized = mapper.readValue<InterEngineMessage>(json)
        assertEquals(msg, deserialized)
    }

    @Test
    fun `WhoResponse round-trips through JSON`() {
        val msg = InterEngineMessage.WhoResponse(
            "req-123",
            listOf(PlayerSummary("Alice", "zone:room1", 5), PlayerSummary("Bob", "zone:room2", 10)),
        )
        val json = mapper.writeValueAsString(msg as InterEngineMessage)
        val deserialized = mapper.readValue<InterEngineMessage>(json)
        assertEquals(msg, deserialized)
    }

    @Test
    fun `PlayerHandoff round-trips through JSON`() {
        val state = SerializedPlayerState(
            playerId = 42L,
            name = "Alice",
            roomId = "zone:room1",
            hp = 20,
            maxHp = 30,
            baseMaxHp = 28,
            constitution = 5,
            level = 10,
            xpTotal = 50000L,
            ansiEnabled = true,
            isStaff = false,
            passwordHash = "hash",
            createdEpochMs = 1000L,
            lastSeenEpochMs = 2000L,
            inventoryItems = listOf(
                SerializedItem(
                    id = "zone:sword",
                    keyword = "sword",
                    displayName = "a sharp sword",
                    slot = "HAND",
                    damage = 5,
                ),
                SerializedItem(
                    id = "zone:shield",
                    keyword = "shield",
                    displayName = "a wooden shield",
                    slot = "BODY",
                    armor = 2,
                ),
            ),
            equippedItems = mapOf(
                "HAND" to SerializedItem(
                    id = "zone:sword",
                    keyword = "sword",
                    displayName = "a sharp sword",
                    slot = "HAND",
                    damage = 5,
                ),
            ),
        )
        val msg = InterEngineMessage.PlayerHandoff(
            sessionId = 99L,
            targetRoomId = "other_zone:room2",
            playerState = state,
            gatewayId = 1,
            sourceEngineId = "engine-1",
        )
        val json = mapper.writeValueAsString(msg as InterEngineMessage)
        val deserialized = mapper.readValue<InterEngineMessage>(json)
        assertEquals(msg, deserialized)
    }

    @Test
    fun `SerializedItem round-trips through JSON`() {
        val item = SerializedItem(
            id = "zone:magic_staff",
            keyword = "staff",
            displayName = "a glowing staff",
            description = "It hums with power.",
            slot = "HAND",
            damage = 7,
            armor = 1,
            constitution = 2,
            matchByKey = true,
        )
        val json = mapper.writeValueAsString(item)
        val deserialized = mapper.readValue<SerializedItem>(json)
        assertEquals(item, deserialized)
    }

    @Test
    fun `ShutdownRequest round-trips through JSON`() {
        val msg = InterEngineMessage.ShutdownRequest("Admin")
        val json = mapper.writeValueAsString(msg as InterEngineMessage)
        val deserialized = mapper.readValue<InterEngineMessage>(json)
        assertEquals(msg, deserialized)
    }

    @Test
    fun `KickRequest round-trips through JSON`() {
        val msg = InterEngineMessage.KickRequest("Troll")
        val json = mapper.writeValueAsString(msg as InterEngineMessage)
        val deserialized = mapper.readValue<InterEngineMessage>(json)
        assertEquals(msg, deserialized)
    }

    @Test
    fun `SessionRedirect round-trips through JSON`() {
        val msg = InterEngineMessage.SessionRedirect(123L, "engine-2", "host2", 9091)
        val json = mapper.writeValueAsString(msg as InterEngineMessage)
        val deserialized = mapper.readValue<InterEngineMessage>(json)
        assertEquals(msg, deserialized)
    }

    @Test
    fun `InterEngineEnvelope round-trips through JSON`() {
        val msg = InterEngineMessage.GlobalBroadcast(BroadcastType.ANNOUNCEMENT, "System", "Restarting")
        val payloadJson = mapper.writeValueAsString(msg as InterEngineMessage)
        val envelope = InterEngineEnvelope(
            senderEngineId = "engine-1",
            targetEngineId = null,
            payload = payloadJson,
        )
        val json = mapper.writeValueAsString(envelope)
        val deserialized = mapper.readValue<InterEngineEnvelope>(json)
        assertEquals(envelope, deserialized)

        // Verify payload can be deserialized back
        val innerMsg = mapper.readValue<InterEngineMessage>(deserialized.payload)
        assertEquals(msg, innerMsg)
    }
}
