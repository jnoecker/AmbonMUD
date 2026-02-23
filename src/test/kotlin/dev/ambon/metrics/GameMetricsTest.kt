package dev.ambon.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GameMetricsTest {
    private val registry = SimpleMeterRegistry()
    private val metrics = GameMetrics(registry)

    @Test
    fun `noop does not throw`() {
        assertDoesNotThrow {
            val noop = GameMetrics.noop()
            noop.onEngineTick()
            noop.onTelnetConnected()
            noop.onTelnetDisconnected("EOF")
            noop.onWsConnected()
            noop.onWsDisconnected("EOF")
            noop.onInboundLineTelnet()
            noop.onInboundLineWs()
            noop.onInboundBackpressureFailure()
            noop.onOutboundFrameEnqueued()
            noop.onOutboundBackpressureDisconnect()
            noop.onOutboundEnqueueFailed()
            noop.onGrpcControlPlaneDrop("stream_full_timeout")
            noop.onGrpcDataPlaneDrop("stream_full")
            noop.onGrpcControlPlaneFallbackSend()
            noop.onGrpcForcedDisconnectDueToControlDeliveryFailure("stream_full_timeout")
            noop.onEngineTickOverrun()
            noop.onInboundEventsProcessed(5)
            noop.onMobMoves(3)
            noop.onCombatsProcessed(2)
            noop.onSchedulerActionsExecuted(1)
            noop.onSchedulerActionsDropped(0)
            noop.onXpAwarded(100L, "kill")
            noop.onLevelUp()
            noop.onPlayerDeath()
            noop.onPlayerSave()
            noop.onPlayerSaveFailure()
        }
    }

    @Test
    fun `telnet connect and disconnect update counters and gauges`() {
        metrics.onTelnetConnected()
        metrics.onTelnetConnected()

        assertEquals(
            2.0,
            registry.counter("sessions_connected_total", "transport", "telnet").count(),
        )
        assertEquals(2.0, registry.get("sessions_online").gauge().value())
        assertEquals(2.0, registry.get("telnet_connections_online").gauge().value())

        metrics.onTelnetDisconnected("EOF")

        assertEquals(1.0, registry.get("sessions_online").gauge().value())
        assertEquals(1.0, registry.get("telnet_connections_online").gauge().value())
        assertEquals(
            1.0,
            registry.counter("sessions_disconnected_total", "transport", "telnet", "reason", "EOF").count(),
        )
    }

    @Test
    fun `ws connect and disconnect update counters and gauges`() {
        metrics.onWsConnected()

        assertEquals(1.0, registry.counter("sessions_connected_total", "transport", "ws").count())
        assertEquals(1.0, registry.get("ws_connections_online").gauge().value())

        metrics.onWsDisconnected("backpressure")

        assertEquals(0.0, registry.get("ws_connections_online").gauge().value())
        assertEquals(
            1.0,
            registry.counter("sessions_disconnected_total", "transport", "ws", "reason", "backpressure").count(),
        )
    }

    @Test
    fun `inbound line counters increment`() {
        metrics.onInboundLineTelnet()
        metrics.onInboundLineTelnet()
        metrics.onInboundLineWs()

        assertEquals(2.0, registry.counter("inbound_lines_total", "transport", "telnet").count())
        assertEquals(1.0, registry.counter("inbound_lines_total", "transport", "ws").count())
    }

    @Test
    fun `engine tick counter and overrun counter`() {
        metrics.onEngineTick()
        metrics.onEngineTick()
        metrics.onEngineTick()
        metrics.onEngineTickOverrun()

        assertEquals(3.0, registry.counter("engine_ticks_total").count())
        assertEquals(1.0, registry.counter("engine_tick_overrun_total").count())
    }

    @Test
    fun `xp awarded increments correct source counter`() {
        metrics.onXpAwarded(500L, "kill")
        metrics.onXpAwarded(200L, "quest")
        metrics.onXpAwarded(100L, "admin")
        metrics.onXpAwarded(75L, "item_use")
        metrics.onXpAwarded(50L, "unknown")

        assertEquals(550.0, registry.counter("xp_awarded_total", "source", "kill").count())
        assertEquals(200.0, registry.counter("xp_awarded_total", "source", "quest").count())
        assertEquals(100.0, registry.counter("xp_awarded_total", "source", "admin").count())
        assertEquals(75.0, registry.counter("xp_awarded_total", "source", "item_use").count())
    }

    @Test
    fun `player save and failure counters`() {
        metrics.onPlayerSave()
        metrics.onPlayerSave()
        metrics.onPlayerSaveFailure()

        assertEquals(2.0, registry.counter("player_saves_total").count())
        assertEquals(1.0, registry.counter("player_save_failures_total").count())
    }

    @Test
    fun `bind suppliers register gauges`() {
        var playerCount = 3
        var mobCount = 7
        var roomCount = 2

        metrics.bindPlayerRegistry { playerCount }
        metrics.bindMobRegistry { mobCount }
        metrics.bindRoomsOccupied { roomCount }

        assertEquals(3.0, registry.get("players_online").gauge().value())
        assertEquals(7.0, registry.get("mobs_alive").gauge().value())
        assertEquals(2.0, registry.get("rooms_occupied").gauge().value())

        playerCount = 5
        assertEquals(5.0, registry.get("players_online").gauge().value())
    }

    @Test
    fun `scheduler action counters`() {
        metrics.onSchedulerActionsExecuted(10)
        metrics.onSchedulerActionsDropped(3)

        assertEquals(10.0, registry.counter("scheduler_actions_executed_total").count())
        assertEquals(3.0, registry.counter("scheduler_actions_dropped_total").count())
    }

    @Test
    fun `grpc control and data plane counters increment`() {
        metrics.onGrpcControlPlaneDrop("stream_full_timeout")
        metrics.onGrpcControlPlaneDrop("stream_full_timeout")
        metrics.onGrpcDataPlaneDrop("stream_full")
        metrics.onGrpcControlPlaneFallbackSend()
        metrics.onGrpcForcedDisconnectDueToControlDeliveryFailure("stream_full_timeout")

        assertEquals(
            2.0,
            registry.counter("grpc_outbound_control_plane_dropped_total", "reason", "stream_full_timeout").count(),
        )
        assertEquals(
            1.0,
            registry.counter("grpc_outbound_data_plane_dropped_total", "reason", "stream_full").count(),
        )
        assertEquals(
            1.0,
            registry.counter("grpc_outbound_control_plane_fallback_total").count(),
        )
        assertEquals(
            1.0,
            registry.counter("grpc_forced_disconnect_control_plane_total", "reason", "stream_full_timeout").count(),
        )
    }
}
