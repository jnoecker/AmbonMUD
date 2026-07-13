package dev.ambon.engine.commands

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.config.WeatherConfig
import dev.ambon.config.WorldEventsConfig
import dev.ambon.config.WorldTimeConfig
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.GmcpEmitter
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class ZoneChartCommandTest {
    private val world = dev.ambon.test.TestWorlds.okSmall
    private val roomA: RoomId = world.startRoom

    @Test
    fun `map with no zone charts the current zone`() =
        runTest {
            val h = harness()
            h.router.handle(h.sid, Command.ZoneChart(null))
            val events = h.outbound.drainAll()
            val texts = events.filterIsInstance<OutboundEvent.SendInfo>().map { it.text }
            assertTrue(texts.any { it.contains("unfurl the charts of Ok Small") }, "Expected summary line: $texts")
            val gmcp = events.filterIsInstance<OutboundEvent.GmcpData>()
            assertTrue(
                gmcp.any { it.gmcpPackage == "Zone.Map" && it.jsonData.contains("\"ok_small\"") },
                "Expected a Zone.Map emission for ok_small: $gmcp",
            )
        }

    @Test
    fun `map with a zone name charts that zone`() =
        runTest {
            val h = harness()
            h.router.handle(h.sid, Command.ZoneChart("Ok Small"))
            val events = h.outbound.drainAll()
            val gmcp = events.filterIsInstance<OutboundEvent.GmcpData>()
            assertTrue(
                gmcp.any { it.gmcpPackage == "Zone.Map" && it.jsonData.contains("\"ok_small\"") },
                "Expected display-style name to resolve to the ok_small chart: $gmcp",
            )
        }

    @Test
    fun `map of an unknown zone errors without a chart`() =
        runTest {
            val h = harness()
            h.router.handle(h.sid, Command.ZoneChart("atlantis"))
            val events = h.outbound.drainAll()
            assertTrue(
                events.filterIsInstance<OutboundEvent.SendError>().any { it.text.contains("atlantis") },
                "Expected an error naming the unknown zone: $events",
            )
            assertTrue(
                events.filterIsInstance<OutboundEvent.GmcpData>().none { it.gmcpPackage == "Zone.Map" },
                "Expected no Zone.Map emission for an unknown zone",
            )
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
            gmcpEmitter = GmcpEmitter(outbound = outbound, supportsPackage = { _, _ -> true }),
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
