package dev.ambon.engine

import dev.ambon.config.CurrenciesConfig
import dev.ambon.config.CurrencyDefinitionConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.persistence.PlayerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CurrencySystemTest {
    private lateinit var system: CurrencySystem
    private lateinit var player: PlayerState

    @BeforeEach
    fun setup() {
        system = CurrencySystem(
            config = CurrenciesConfig(
                definitions = mapOf(
                    "quest_points" to CurrencyDefinitionConfig(
                        displayName = "Quest Points",
                        abbreviation = "QP",
                        description = "Earned from completing quests",
                    ),
                    "honor" to CurrencyDefinitionConfig(
                        displayName = "Honor",
                        abbreviation = "Hon",
                        description = "Earned from PvP kills",
                    ),
                    "crafting_tokens" to CurrencyDefinitionConfig(
                        displayName = "Crafting Tokens",
                        abbreviation = "CT",
                        description = "Earned from crafting activities",
                    ),
                ),
                honorPerPvpKill = 10L,
                tokensPerCraft = 1L,
            ),
        )
        player = PlayerState(
            sessionId = SessionId(1),
            name = "TestHero",
            roomId = RoomId("zone:room"),
        )
    }

    @Test
    fun `balance returns zero for unset currency`() {
        assertEquals(0L, system.balance(player, "quest_points"))
    }

    @Test
    fun `award increases balance`() {
        system.award(player, "quest_points", 50)
        assertEquals(50L, system.balance(player, "quest_points"))
    }

    @Test
    fun `award accumulates`() {
        system.award(player, "honor", 10)
        system.award(player, "honor", 15)
        assertEquals(25L, system.balance(player, "honor"))
    }

    @Test
    fun `award with zero amount does nothing`() {
        system.award(player, "quest_points", 0)
        assertEquals(0L, system.balance(player, "quest_points"))
    }

    @Test
    fun `award with negative amount does nothing`() {
        system.award(player, "quest_points", -5)
        assertEquals(0L, system.balance(player, "quest_points"))
    }

    @Test
    fun `award unknown currency does nothing`() {
        system.award(player, "unknown_currency", 100)
        assertFalse(player.currencies.containsKey("unknown_currency"))
    }

    @Test
    fun `spend deducts from balance`() {
        system.award(player, "quest_points", 100)
        val result = system.spend(player, "quest_points", 30)
        assertTrue(result)
        assertEquals(70L, system.balance(player, "quest_points"))
    }

    @Test
    fun `spend fails when insufficient balance`() {
        system.award(player, "quest_points", 10)
        val result = system.spend(player, "quest_points", 20)
        assertFalse(result)
        assertEquals(10L, system.balance(player, "quest_points"))
    }

    @Test
    fun `spend fails for unknown currency`() {
        val result = system.spend(player, "nonexistent", 5)
        assertFalse(result)
    }

    @Test
    fun `spend fails for zero amount`() {
        system.award(player, "honor", 10)
        val result = system.spend(player, "honor", 0)
        assertFalse(result)
        assertEquals(10L, system.balance(player, "honor"))
    }

    @Test
    fun `spend fails for negative amount`() {
        system.award(player, "honor", 10)
        val result = system.spend(player, "honor", -5)
        assertFalse(result)
        assertEquals(10L, system.balance(player, "honor"))
    }

    @Test
    fun `spend exact balance succeeds`() {
        system.award(player, "crafting_tokens", 5)
        val result = system.spend(player, "crafting_tokens", 5)
        assertTrue(result)
        assertEquals(0L, system.balance(player, "crafting_tokens"))
    }

    @Test
    fun `allBalances includes all defined currencies`() {
        system.award(player, "quest_points", 50)
        val balances = system.allBalances(player)
        assertEquals(3, balances.size)
        assertEquals(50L, balances["quest_points"])
        assertEquals(0L, balances["honor"])
        assertEquals(0L, balances["crafting_tokens"])
    }

    @Test
    fun `definitions returns config definitions`() {
        val defs = system.definitions()
        assertEquals(3, defs.size)
        assertEquals("Quest Points", defs["quest_points"]?.displayName)
        assertEquals("QP", defs["quest_points"]?.abbreviation)
    }

    @Test
    fun `getDefinition returns definition or null`() {
        val def = system.getDefinition("honor")
        assertEquals("Honor", def?.displayName)
        assertEquals(null, system.getDefinition("nonexistent"))
    }

    @Test
    fun `honorPerPvpKill returns config value`() {
        assertEquals(10L, system.honorPerPvpKill)
    }

    @Test
    fun `tokensPerCraft returns config value`() {
        assertEquals(1L, system.tokensPerCraft)
    }

    @Test
    fun `persistence round-trip via PlayerState currencies map`() {
        player.playerId = PlayerId(42L)
        system.award(player, "quest_points", 100)
        system.award(player, "honor", 50)
        system.award(player, "crafting_tokens", 25)

        // Simulate persistence round-trip
        val record = player.toPlayerRecord(lastSeenEpochMs = 0L)
        assertEquals(mapOf("quest_points" to 100L, "honor" to 50L, "crafting_tokens" to 25L), record.currencies)

        val restored = record.toPlayerState(SessionId(2))
        assertEquals(100L, system.balance(restored, "quest_points"))
        assertEquals(50L, system.balance(restored, "honor"))
        assertEquals(25L, system.balance(restored, "crafting_tokens"))
    }
}
