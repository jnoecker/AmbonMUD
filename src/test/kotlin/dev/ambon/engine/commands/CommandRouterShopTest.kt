package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.EconomyConfig
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.ShopRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterShopTest {
    @Test
    fun `balance shows gold`() =
        runTest {
            val env = setup()
            env.player.gold = 42L

            env.router.handle(env.sid, Command.Balance)

            val outs = env.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("42 gold") },
                "Expected balance message containing '42 gold'. got=$outs",
            )
        }

    @Test
    fun `list shows shop items with prices`() =
        runTest {
            val env = setup()

            env.router.handle(env.sid, Command.ShopList)

            val outs = env.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("Market Vendor") },
                "Expected shop name in listing. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("a steel sword") },
                "Expected sword in listing. got=$outs",
            )
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("a health potion") },
                "Expected potion in listing. got=$outs",
            )
        }

    @Test
    fun `list outside shop room shows error`() =
        runTest {
            val env = setup()
            // Move player to alley (no shop)
            env.players.moveTo(env.sid, RoomId("ok_shop:alley"))

            env.router.handle(env.sid, Command.ShopList)

            val outs = env.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("no shop") },
                "Expected 'no shop' message. got=$outs",
            )
        }

    @Test
    fun `buy deducts gold and adds item to inventory`() =
        runTest {
            val env = setup()
            env.player.gold = 100L

            env.router.handle(env.sid, Command.Buy("sword"))

            assertEquals(50L, env.player.gold)
            val inv = env.items.inventory(env.sid)
            assertEquals(1, inv.size)
            assertEquals("sword", inv[0].item.keyword)

            val outs = env.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("You buy") && it.text.contains("50 gold") },
                "Expected buy confirmation. got=$outs",
            )
        }

    @Test
    fun `buy with insufficient gold fails`() =
        runTest {
            val env = setup()
            env.player.gold = 10L

            env.router.handle(env.sid, Command.Buy("sword"))

            assertEquals(10L, env.player.gold)
            assertTrue(env.items.inventory(env.sid).isEmpty())

            val outs = env.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("afford") },
                "Expected 'cannot afford' message. got=$outs",
            )
        }

    @Test
    fun `buy outside shop room fails`() =
        runTest {
            val env = setup()
            env.player.gold = 100L
            env.players.moveTo(env.sid, RoomId("ok_shop:alley"))

            env.router.handle(env.sid, Command.Buy("sword"))

            assertEquals(100L, env.player.gold)
            assertTrue(env.items.inventory(env.sid).isEmpty())

            val outs = env.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("no shop") },
                "Expected 'no shop' message. got=$outs",
            )
        }

    @Test
    fun `buy unknown item fails`() =
        runTest {
            val env = setup()
            env.player.gold = 100L

            env.router.handle(env.sid, Command.Buy("nonexistent"))

            assertEquals(100L, env.player.gold)

            val outs = env.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("doesn't sell") },
                "Expected 'doesn't sell' message. got=$outs",
            )
        }

    @Test
    fun `sell adds gold and removes item from inventory`() =
        runTest {
            val env = setup()
            env.player.gold = 0L
            env.items.addToInventory(
                env.sid,
                ItemInstance(ItemId("ok_shop:sword"), Item(keyword = "sword", displayName = "a steel sword", basePrice = 50)),
            )

            env.router.handle(env.sid, Command.Sell("sword"))

            assertEquals(25L, env.player.gold) // 50 * 0.5 = 25
            assertTrue(env.items.inventory(env.sid).isEmpty())

            val outs = env.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("You sell") && it.text.contains("25 gold") },
                "Expected sell confirmation. got=$outs",
            )
        }

    @Test
    fun `sell item with zero basePrice is rejected as worthless`() =
        runTest {
            val env = setup()
            env.player.gold = 0L
            env.items.addToInventory(
                env.sid,
                ItemInstance(ItemId("ok_shop:trophy"), Item(keyword = "trophy", displayName = "a trophy skull", basePrice = 0)),
            )

            env.router.handle(env.sid, Command.Sell("trophy"))

            assertEquals(0L, env.player.gold)
            assertEquals(1, env.items.inventory(env.sid).size)

            val outs = env.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("worthless") },
                "Expected 'worthless' message. got=$outs",
            )
        }

    @Test
    fun `sell outside shop room fails`() =
        runTest {
            val env = setup()
            env.player.gold = 0L
            env.items.addToInventory(
                env.sid,
                ItemInstance(ItemId("ok_shop:sword"), Item(keyword = "sword", displayName = "a steel sword", basePrice = 50)),
            )
            env.players.moveTo(env.sid, RoomId("ok_shop:alley"))

            env.router.handle(env.sid, Command.Sell("sword"))

            assertEquals(0L, env.player.gold)
            assertEquals(1, env.items.inventory(env.sid).size)

            val outs = env.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("no shop") },
                "Expected 'no shop' message. got=$outs",
            )
        }

    @Test
    fun `sell item not in inventory fails`() =
        runTest {
            val env = setup()
            env.player.gold = 0L

            env.router.handle(env.sid, Command.Sell("sword"))

            assertEquals(0L, env.player.gold)

            val outs = env.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("don't have") },
                "Expected 'don't have' message. got=$outs",
            )
        }

    @Test
    fun `sell item with low basePrice that rounds to zero is rejected as worthless`() =
        runTest {
            val economy = EconomyConfig(buyMultiplier = 1.0, sellMultiplier = 0.1)
            val env = setup(economyConfig = economy)
            env.player.gold = 0L
            // basePrice=2, sellMultiplier=0.1 → 0.2 → roundToInt = 0
            env.items.addToInventory(
                env.sid,
                ItemInstance(
                    ItemId("ok_shop:junk"),
                    Item(keyword = "junk", displayName = "a bit of junk", basePrice = 2),
                ),
            )

            env.router.handle(env.sid, Command.Sell("junk"))

            assertEquals(0L, env.player.gold)
            assertEquals(1, env.items.inventory(env.sid).size)

            val outs = env.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("worthless") },
                "Expected 'worthless' message for 0-value sell. got=$outs",
            )
        }

    @Test
    fun `buy with custom economy multiplier applies correct price`() =
        runTest {
            val economy = EconomyConfig(buyMultiplier = 2.0, sellMultiplier = 0.25)
            val env = setup(economyConfig = economy)
            env.player.gold = 200L

            env.router.handle(env.sid, Command.Buy("potion"))

            // 20 * 2.0 = 40
            assertEquals(160L, env.player.gold)
            assertEquals(1, env.items.inventory(env.sid).size)
        }

    private data class TestEnv(
        val world: dev.ambon.domain.world.World,
        val items: ItemRegistry,
        val players: PlayerRegistry,
        val mobs: MobRegistry,
        val outbound: LocalOutboundBus,
        val router: CommandRouter,
        val sid: SessionId,
        val player: dev.ambon.engine.PlayerState,
    ) {
        fun drain(): List<OutboundEvent> {
            val out = mutableListOf<OutboundEvent>()
            while (true) {
                val v = outbound.tryReceive().getOrNull() ?: break
                out.add(v)
            }
            return out
        }
    }

    private suspend fun setup(
        economyConfig: EconomyConfig =
            EconomyConfig(
                buyMultiplier = 1.0,
                sellMultiplier = 0.5,
            ),
    ): TestEnv {
        val world = WorldLoader.loadFromResource("world/ok_shop.yaml")
        val items = ItemRegistry()
        items.loadSpawns(world.itemSpawns)
        val players = PlayerRegistry(world.startRoom, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val outbound = LocalOutboundBus()
        val shopRegistry = ShopRegistry(items)
        shopRegistry.register(world.shopDefinitions)
        val router =
            CommandRouter(
                world,
                players,
                mobs,
                items,
                CombatSystem(players, mobs, items, outbound),
                outbound,
                shopRegistry = shopRegistry,
                economyConfig = economyConfig,
            )

        val sid = SessionId(1L)
        val res = players.login(sid, "Shopper", "password")
        require(res == LoginResult.Ok) { "Login failed: $res" }
        // Drain login-related output
        while (outbound.tryReceive().getOrNull() != null) { /* drain */ }

        val player = players.get(sid)!!
        return TestEnv(world, items, players, mobs, outbound, router, sid, player)
    }
}
