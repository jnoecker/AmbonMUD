package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.WeatherConfig
import dev.ambon.config.WorldEventsConfig
import dev.ambon.config.WorldTimeConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.LoginResult
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.WeatherSystem
import dev.ambon.engine.WorldEventSystem
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.WorldTimeSystem
import dev.ambon.engine.commands.handlers.EngineContext
import dev.ambon.engine.commands.handlers.WorldInfoHandler
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.persistence.InMemoryPlayerRepository
import dev.ambon.test.drainAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class AreasCommandTest {
    private val world = dev.ambon.test.TestWorlds.okSmall
    private val roomA: RoomId = world.startRoom

    @Test
    fun `areas with no filter lists known zones`() =
        runTest {
            val h = harness()
            h.router.handle(h.sid, Command.Areas(null, null))
            val texts = h.outbound.drainAll().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(texts.any { it.startsWith("[ Areas") }, "Expected header, got: $texts")
            assertTrue(texts.any { it.contains("ok_small") }, "Expected ok_small zone in output: $texts")
        }

    @Test
    fun `areas with level filter excludes zones outside range`() =
        runTest {
            val h = harness()
            // ok_small's only mob is level 1 (default); filter to 50-60 should exclude it.
            h.router.handle(h.sid, Command.Areas(50, 60))
            val texts = h.outbound.drainAll().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertFalse(texts.any { it.contains("ok_small") }, "Expected ok_small to be filtered out: $texts")
            assertTrue(texts.any { it.contains("no matching areas") }, "Expected empty placeholder: $texts")
        }

    @Test
    fun `areas with overlapping range includes zone`() =
        runTest {
            val h = harness()
            h.router.handle(h.sid, Command.Areas(1, 5))
            val texts = h.outbound.drainAll().filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(texts.any { it.contains("ok_small") }, "Expected ok_small zone in output: $texts")
        }

    private data class Harness(
        val sid: SessionId,
        val router: CommandRouter,
        val outbound: LocalOutboundBus,
    )

    private suspend fun harness(): Harness {
        val items = ItemRegistry()
        items.loadSpawns(world.itemSpawns)
        val outbound = LocalOutboundBus()
        val players = dev.ambon.test.buildTestPlayerRegistry(roomA, InMemoryPlayerRepository(), items)
        val mobs = MobRegistry()
        val worldState = WorldStateRegistry(world)
        val router = CommandRouter(outbound = outbound, players = players)
        val ctx = EngineContext(
            players = players,
            mobs = mobs,
            world = world,
            items = items,
            outbound = outbound,
            combat = CombatSystem(players, mobs, items, outbound),
            gmcpEmitter = null,
            worldState = worldState,
        )
        val clock = Clock.systemUTC()
        WorldInfoHandler(
            ctx = ctx,
            worldTimeSystem = WorldTimeSystem(WorldTimeConfig(), clock),
            weatherSystem = WeatherSystem(WeatherConfig(), clock),
            worldEventSystem = WorldEventSystem(WorldEventsConfig(), clock),
        ).register(router)
        val sid = SessionId(1)
        val res = players.login(sid, "Cartograph", "password")
        require(res == LoginResult.Ok)
        outbound.drainAll()
        return Harness(sid, router, outbound)
    }
}
