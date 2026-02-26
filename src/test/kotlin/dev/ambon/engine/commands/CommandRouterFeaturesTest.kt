package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.world.ContainerState
import dev.ambon.domain.world.Direction
import dev.ambon.domain.world.LeverState
import dev.ambon.domain.world.load.WorldLoader
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommandRouterFeaturesTest {
    private val world = WorldLoader.loadFromResource("world/ok_features.yaml")
    private val startRoomId = world.startRoom // ok_features:entrance

    // ---- test fixture ----

    private data class Harness(
        val sid: SessionId,
        val players: PlayerRegistry,
        val items: ItemRegistry,
        val router: CommandRouter,
        val outbound: LocalOutboundBus,
        val worldState: WorldStateRegistry,
    )

    private suspend fun harness(): Harness {
        val items = ItemRegistry()
        items.loadSpawns(world.itemSpawns)
        val outbound = LocalOutboundBus()
        val players = PlayerRegistry(startRoomId, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val worldState = WorldStateRegistry(world)
        val router =
            buildTestRouter(
                world = world,
                players = players,
                mobs = mobs,
                items = items,
                combat = CombatSystem(players, mobs, items, outbound),
                outbound = outbound,
                worldState = worldState,
            )
        val sid = SessionId(1)
        val res = players.login(sid, "Tester", "password")
        require(res == LoginResult.Ok)
        outbound.drain()
        return Harness(sid, players, items, router, outbound, worldState)
    }

    private fun LocalOutboundBus.drain(): List<OutboundEvent> {
        val out = mutableListOf<OutboundEvent>()
        while (true) out += tryReceive().getOrNull() ?: break
        return out
    }

    private fun makeKey() =
        ItemInstance(
            id = ItemId("ok_features:brass_key"),
            item = Item(keyword = "key", displayName = "a brass key", description = ""),
        )

    private fun makeCoin() =
        ItemInstance(
            id = ItemId("ok_features:silver_coin"),
            item = Item(keyword = "coin", displayName = "a silver coin", description = ""),
        )

    // ---- door tests ----

    @Test
    fun `locked door blocks movement`() =
        runTest {
            val h = harness()
            h.router.handle(h.sid, Command.Move(Direction.NORTH))
            val outs = h.outbound.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendText && it.text.contains("locked", ignoreCase = true) },
                "Expected blocked message, got: $outs",
            )
            assertEquals(startRoomId, h.players.get(h.sid)?.roomId)
        }

    @Test
    fun `unlock door with key succeeds`() =
        runTest {
            val h = harness()
            h.items.addToInventory(h.sid, makeKey())

            h.router.handle(h.sid, Command.UnlockFeature("n"))
            val outs = h.outbound.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("unlock", ignoreCase = true) },
                "Expected unlock message, got: $outs",
            )
        }

    @Test
    fun `unlock door without key fails`() =
        runTest {
            val h = harness()
            h.router.handle(h.sid, Command.UnlockFeature("n"))
            val outs = h.outbound.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendError },
                "Expected error, got: $outs",
            )
        }

    @Test
    fun `open closed door succeeds`() =
        runTest {
            val h = harness()
            // Unlock first
            h.items.addToInventory(h.sid, makeKey())
            h.router.handle(h.sid, Command.UnlockFeature("n"))
            h.outbound.drain()

            h.router.handle(h.sid, Command.OpenFeature("n"))
            val outs = h.outbound.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("open", ignoreCase = true) },
                "Expected open message, got: $outs",
            )
        }

    @Test
    fun `open locked door fails with error`() =
        runTest {
            val h = harness()
            h.router.handle(h.sid, Command.OpenFeature("n"))
            val outs = h.outbound.drain()
            assertTrue(outs.any { it is OutboundEvent.SendError }, "Expected error, got: $outs")
        }

    @Test
    fun `close open door succeeds`() =
        runTest {
            val h = harness()
            h.items.addToInventory(h.sid, makeKey())
            h.router.handle(h.sid, Command.UnlockFeature("n"))
            h.router.handle(h.sid, Command.OpenFeature("n"))
            h.outbound.drain()

            h.router.handle(h.sid, Command.CloseFeature("n"))
            val outs = h.outbound.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("close", ignoreCase = true) },
                "Expected close message, got: $outs",
            )
        }

    // ---- container tests ----

    @Test
    fun `search closed container returns error`() =
        runTest {
            val h = harness()
            h.players.moveTo(h.sid, RoomId("ok_features:storeroom"))
            h.outbound.drain()

            h.router.handle(h.sid, Command.SearchContainer("chest"))
            val outs = h.outbound.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendError },
                "Expected error for closed container, got: $outs",
            )
        }

    @Test
    fun `open container and search shows contents`() =
        runTest {
            val h = harness()
            val containerId = "ok_features:storeroom/supply_chest"
            h.worldState.addToContainer(containerId, makeCoin())
            h.worldState.setContainerState(containerId, ContainerState.OPEN)
            h.worldState.clearDirty()

            h.players.moveTo(h.sid, RoomId("ok_features:storeroom"))
            h.outbound.drain()

            h.router.handle(h.sid, Command.SearchContainer("chest"))
            val outs = h.outbound.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("coin", ignoreCase = true) },
                "Expected coin in search results, got: $outs",
            )
        }

    @Test
    fun `get from open container moves item to inventory`() =
        runTest {
            val h = harness()
            val containerId = "ok_features:storeroom/supply_chest"
            h.worldState.addToContainer(containerId, makeCoin())
            h.worldState.setContainerState(containerId, ContainerState.OPEN)
            h.worldState.clearDirty()

            h.players.moveTo(h.sid, RoomId("ok_features:storeroom"))
            h.outbound.drain()

            h.router.handle(h.sid, Command.GetFrom("coin", "chest"))
            val outs = h.outbound.drain()
            assertTrue(outs.any { it is OutboundEvent.SendInfo }, "Expected success, got: $outs")
            assertTrue(h.items.inventory(h.sid).any { it.item.keyword == "coin" })
            assertTrue(h.worldState.getContainerContents(containerId).isEmpty())
        }

    @Test
    fun `put item in open container moves item from inventory`() =
        runTest {
            val h = harness()
            val containerId = "ok_features:storeroom/supply_chest"
            h.worldState.setContainerState(containerId, ContainerState.OPEN)
            h.worldState.clearDirty()
            h.items.addToInventory(h.sid, makeCoin())

            h.players.moveTo(h.sid, RoomId("ok_features:storeroom"))
            h.outbound.drain()

            h.router.handle(h.sid, Command.PutIn("coin", "chest"))
            val outs = h.outbound.drain()
            assertTrue(outs.any { it is OutboundEvent.SendInfo }, "Expected success, got: $outs")
            assertTrue(h.items.inventory(h.sid).isEmpty())
            assertFalse(h.worldState.getContainerContents(containerId).isEmpty())
        }

    // ---- lever test ----

    @Test
    fun `pull lever toggles lever state`() =
        runTest {
            val h = harness()
            h.players.moveTo(h.sid, RoomId("ok_features:vault"))
            h.outbound.drain()

            h.router.handle(h.sid, Command.Pull("lever"))
            val outs = h.outbound.drain()
            assertTrue(outs.any { it is OutboundEvent.SendInfo }, "Expected success, got: $outs")
            assertEquals(LeverState.DOWN, h.worldState.getLeverState("ok_features:vault/iron_lever"))
        }

    // ---- sign test ----

    @Test
    fun `read sign shows text`() =
        runTest {
            val h = harness()
            h.router.handle(h.sid, Command.ReadSign("board"))
            val outs = h.outbound.drain()
            assertTrue(
                outs.any { it is OutboundEvent.SendInfo && it.text.contains("Welcome", ignoreCase = true) },
                "Expected sign text, got: $outs",
            )
        }

    @Test
    fun `read unknown feature returns error`() =
        runTest {
            val h = harness()
            h.router.handle(h.sid, Command.ReadSign("xyzzy"))
            val outs = h.outbound.drain()
            assertTrue(outs.any { it is OutboundEvent.SendError }, "Expected error, got: $outs")
        }
}
