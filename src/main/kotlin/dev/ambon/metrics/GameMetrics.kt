package dev.ambon.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.concurrent.atomic.AtomicInteger

class GameMetrics(
    private val registry: MeterRegistry,
    private val bindJvmMetrics: Boolean = true,
) {
    init {
        if (bindJvmMetrics) {
            JvmMemoryMetrics().bindTo(registry)
            JvmGcMetrics().bindTo(registry)
            JvmThreadMetrics().bindTo(registry)
            ClassLoaderMetrics().bindTo(registry)
            ProcessorMetrics().bindTo(registry)
            UptimeMetrics().bindTo(registry)
        }
    }

    private val sessionsOnlineCount = AtomicInteger(0)
    private val telnetOnlineCount = AtomicInteger(0)
    private val wsOnlineCount = AtomicInteger(0)

    init {
        Gauge.builder("sessions_online") { sessionsOnlineCount.get().toDouble() }.register(registry)
        Gauge.builder("telnet_connections_online") { telnetOnlineCount.get().toDouble() }.register(registry)
        Gauge.builder("ws_connections_online") { wsOnlineCount.get().toDouble() }.register(registry)
    }

    private val telnetConnectedCounter =
        Counter.builder("sessions_connected_total").tag("transport", "telnet").register(registry)
    private val wsConnectedCounter =
        Counter.builder("sessions_connected_total").tag("transport", "ws").register(registry)

    private val inboundLineTelnetCounter =
        Counter.builder("inbound_lines_total").tag("transport", "telnet").register(registry)
    private val inboundLineWsCounter =
        Counter.builder("inbound_lines_total").tag("transport", "ws").register(registry)
    private val inboundBackpressureFailuresCounter =
        Counter.builder("inbound_backpressure_failures_total").register(registry)

    private val outboundFramesCounter =
        Counter.builder("outbound_frames_total").register(registry)
    private val outboundBackpressureDisconnectsCounter =
        Counter.builder("outbound_backpressure_disconnects_total").register(registry)
    private val outboundEnqueueFailedCounter =
        Counter.builder("outbound_enqueue_failed_total").register(registry)
    private val grpcControlPlaneFallbackCounter =
        Counter.builder("grpc_outbound_control_plane_fallback_total").register(registry)

    val engineTickTimer: Timer =
        Timer
            .builder("engine_tick_duration_seconds")
            .publishPercentileHistogram()
            .register(registry)
    private val engineTicksCounter = Counter.builder("engine_ticks_total").register(registry)
    private val engineTickOverrunCounter = Counter.builder("engine_tick_overrun_total").register(registry)
    private val inboundEventsProcessedCounter =
        Counter.builder("engine_inbound_events_processed_total").register(registry)

    val mobSystemTickTimer: Timer =
        Timer
            .builder("mob_system_tick_duration_seconds")
            .publishPercentileHistogram()
            .register(registry)
    val combatSystemTickTimer: Timer =
        Timer
            .builder("combat_system_tick_duration_seconds")
            .publishPercentileHistogram()
            .register(registry)
    val regenTickTimer: Timer = Timer.builder("regen_tick_duration_seconds").register(registry)
    val schedulerRunDueTimer: Timer =
        Timer
            .builder("scheduler_run_due_duration_seconds")
            .publishPercentileHistogram()
            .register(registry)

    private val mobMovesCounter = Counter.builder("mob_moves_total").register(registry)
    private val combatsProcessedCounter = Counter.builder("combats_processed_total").register(registry)
    private val schedulerActionsExecutedCounter =
        Counter.builder("scheduler_actions_executed_total").register(registry)
    private val schedulerActionsDroppedCounter =
        Counter.builder("scheduler_actions_dropped_total").register(registry)

    private val xpAwardedKillCounter =
        Counter.builder("xp_awarded_total").tag("source", "kill").register(registry)
    private val xpAwardedQuestCounter =
        Counter.builder("xp_awarded_total").tag("source", "quest").register(registry)
    private val xpAwardedAdminCounter =
        Counter.builder("xp_awarded_total").tag("source", "admin").register(registry)
    private val xpAwardedItemUseCounter =
        Counter.builder("xp_awarded_total").tag("source", "item_use").register(registry)
    private val levelUpsCounter = Counter.builder("level_ups_total").register(registry)
    private val playerDeathsCounter = Counter.builder("player_deaths_total").register(registry)

    val playerRepoSaveTimer: Timer = Timer.builder("player_repo_save_duration_seconds").register(registry)
    val playerRepoLoadTimer: Timer = Timer.builder("player_repo_load_duration_seconds").register(registry)
    private val playerSavesCounter = Counter.builder("player_saves_total").register(registry)
    private val playerSaveFailuresCounter = Counter.builder("player_save_failures_total").register(registry)

    private val sessionIdSequenceOverflowCounter =
        Counter.builder("session_id_sequence_overflow_total").register(registry)
    private val sessionIdClockRollbackCounter =
        Counter.builder("session_id_clock_rollback_total").register(registry)

    private val gatewayReconnectAttemptsCounter =
        Counter.builder("gateway_reconnect_attempts_total").register(registry)
    private val gatewayReconnectSuccessCounter =
        Counter.builder("gateway_reconnect_success_total").register(registry)
    private val gatewayReconnectBudgetExhaustedCounter =
        Counter.builder("gateway_reconnect_budget_exhausted_total").register(registry)

    fun onTelnetConnected() {
        telnetConnectedCounter.increment()
        sessionsOnlineCount.incrementAndGet()
        telnetOnlineCount.incrementAndGet()
    }

    fun onTelnetDisconnected(reason: String) {
        registry.counter("sessions_disconnected_total", "transport", "telnet", "reason", reason).increment()
        sessionsOnlineCount.decrementAndGet()
        telnetOnlineCount.decrementAndGet()
    }

    fun onWsConnected() {
        wsConnectedCounter.increment()
        sessionsOnlineCount.incrementAndGet()
        wsOnlineCount.incrementAndGet()
    }

    fun onWsDisconnected(reason: String) {
        registry.counter("sessions_disconnected_total", "transport", "ws", "reason", reason).increment()
        sessionsOnlineCount.decrementAndGet()
        wsOnlineCount.decrementAndGet()
    }

    fun onInboundLineTelnet() = inboundLineTelnetCounter.increment()

    fun onInboundLineWs() = inboundLineWsCounter.increment()

    fun onInboundBackpressureFailure() = inboundBackpressureFailuresCounter.increment()

    fun onOutboundFrameEnqueued() = outboundFramesCounter.increment()

    fun onOutboundBackpressureDisconnect() = outboundBackpressureDisconnectsCounter.increment()

    fun onOutboundEnqueueFailed() = outboundEnqueueFailedCounter.increment()

    fun onGrpcControlPlaneDrop(reason: String) {
        registry.counter("grpc_outbound_control_plane_dropped_total", "reason", reason).increment()
    }

    fun onGrpcDataPlaneDrop(reason: String) {
        registry.counter("grpc_outbound_data_plane_dropped_total", "reason", reason).increment()
    }

    fun onGrpcControlPlaneFallbackSend() = grpcControlPlaneFallbackCounter.increment()

    fun onGrpcForcedDisconnectDueToControlDeliveryFailure(reason: String) {
        registry.counter("grpc_forced_disconnect_control_plane_total", "reason", reason).increment()
    }

    fun onEngineTick() = engineTicksCounter.increment()

    fun onEngineTickOverrun() = engineTickOverrunCounter.increment()

    fun onInboundEventsProcessed(count: Int) = inboundEventsProcessedCounter.increment(count.toDouble())

    fun onMobMoves(count: Int) = mobMovesCounter.increment(count.toDouble())

    fun onCombatsProcessed(count: Int) = combatsProcessedCounter.increment(count.toDouble())

    fun onSchedulerActionsExecuted(count: Int) = schedulerActionsExecutedCounter.increment(count.toDouble())

    fun onSchedulerActionsDropped(count: Int) = schedulerActionsDroppedCounter.increment(count.toDouble())

    fun onXpAwarded(
        amount: Long,
        source: String,
    ) {
        when (source) {
            "kill" -> xpAwardedKillCounter.increment(amount.toDouble())
            "quest" -> xpAwardedQuestCounter.increment(amount.toDouble())
            "admin" -> xpAwardedAdminCounter.increment(amount.toDouble())
            "item_use" -> xpAwardedItemUseCounter.increment(amount.toDouble())
            else -> xpAwardedKillCounter.increment(amount.toDouble())
        }
    }

    fun onLevelUp() = levelUpsCounter.increment()

    fun onPlayerDeath() = playerDeathsCounter.increment()

    fun onPlayerSave() = playerSavesCounter.increment()

    fun onPlayerSaveFailure() = playerSaveFailuresCounter.increment()

    /**
     * Wraps a repository load operation with timing.
     * Records elapsed time against [playerRepoLoadTimer].
     */
    inline fun <T> timedLoad(block: () -> T): T {
        val sample = Timer.start()
        return try {
            block()
        } finally {
            sample.stop(playerRepoLoadTimer)
        }
    }

    /**
     * Wraps a repository save operation with timing and success/failure counters.
     * Records elapsed time against [playerRepoSaveTimer] and increments
     * [onPlayerSave] or [onPlayerSaveFailure] accordingly.
     */
    inline fun timedSave(block: () -> Unit) {
        val sample = Timer.start()
        try {
            block()
            onPlayerSave()
        } catch (e: Throwable) {
            onPlayerSaveFailure()
            throw e
        } finally {
            sample.stop(playerRepoSaveTimer)
        }
    }

    fun onSessionIdSequenceOverflow() = sessionIdSequenceOverflowCounter.increment()

    fun onSessionIdClockRollback() = sessionIdClockRollbackCounter.increment()

    fun onGatewayReconnectAttempt() = gatewayReconnectAttemptsCounter.increment()

    fun onGatewayReconnectSuccess() = gatewayReconnectSuccessCounter.increment()

    fun onGatewayReconnectBudgetExhausted() = gatewayReconnectBudgetExhaustedCounter.increment()

    fun bindPlayerRegistry(supplier: () -> Int) {
        Gauge.builder("players_online") { supplier().toDouble() }.register(registry)
    }

    fun bindMobRegistry(supplier: () -> Int) {
        Gauge.builder("mobs_alive") { supplier().toDouble() }.register(registry)
    }

    fun bindRoomsOccupied(supplier: () -> Int) {
        Gauge.builder("rooms_occupied") { supplier().toDouble() }.register(registry)
    }

    fun bindInboundBusQueue(
        depthSupplier: () -> Int,
        capacitySupplier: () -> Int,
    ) {
        Gauge.builder("inbound_bus_queue_depth") { depthSupplier().toDouble() }.register(registry)
        Gauge.builder("inbound_bus_queue_capacity") { capacitySupplier().toDouble() }.register(registry)
    }

    fun bindOutboundBusQueue(
        depthSupplier: () -> Int,
        capacitySupplier: () -> Int,
    ) {
        Gauge.builder("outbound_bus_queue_depth") { depthSupplier().toDouble() }.register(registry)
        Gauge.builder("outbound_bus_queue_capacity") { capacitySupplier().toDouble() }.register(registry)
    }

    fun bindSessionOutboundQueueAggregate(
        totalDepthSupplier: () -> Int,
        maxDepthSupplier: () -> Int,
    ) {
        Gauge.builder("session_outbound_queue_depth_total") { totalDepthSupplier().toDouble() }.register(registry)
        Gauge.builder("session_outbound_queue_depth_max") { maxDepthSupplier().toDouble() }.register(registry)
    }

    fun bindWriteCoalescerDirtyCount(supplier: () -> Int) {
        Gauge.builder("write_coalescer_dirty_count") { supplier().toDouble() }.register(registry)
    }

    fun bindSchedulerPendingActions(supplier: () -> Int) {
        Gauge.builder("scheduler_pending_actions") { supplier().toDouble() }.register(registry)
    }

    fun bindZonePlayerCount(
        zone: String,
        supplier: () -> Int,
    ) {
        Gauge
            .builder("zone_player_count") { supplier().toDouble() }
            .tag("zone", zone)
            .register(registry)
    }

    companion object {
        fun noop(): GameMetrics = GameMetrics(SimpleMeterRegistry(), bindJvmMetrics = false)
    }
}
