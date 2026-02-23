package dev.ambon.swarm

import java.util.concurrent.atomic.AtomicLong

class SwarmMetrics {
    private val connectOk = AtomicLong(0)
    private val connectFailed = AtomicLong(0)
    private val loginOk = AtomicLong(0)
    private val loginFailed = AtomicLong(0)
    private val commandSent = AtomicLong(0)
    private val intentionalDisconnects = AtomicLong(0)
    private val droppedConnections = AtomicLong(0)

    private val connectLatenciesMs = mutableListOf<Long>()
    private val loginLatenciesMs = mutableListOf<Long>()
    private val lock = Any()

    fun connectionSucceeded(latencyMs: Long) {
        connectOk.incrementAndGet()
        synchronized(lock) { connectLatenciesMs += latencyMs }
    }

    fun connectionFailed() {
        connectFailed.incrementAndGet()
    }

    fun loginSucceeded() {
        loginOk.incrementAndGet()
    }

    fun loginFailed() {
        loginFailed.incrementAndGet()
    }

    fun commandSent() {
        commandSent.incrementAndGet()
    }

    fun intentionalDisconnect() {
        intentionalDisconnects.incrementAndGet()
    }

    fun connectionDropped() {
        droppedConnections.incrementAndGet()
    }

    fun loginLatency(latencyMs: Long) {
        synchronized(lock) { loginLatenciesMs += latencyMs }
    }

    fun snapshotLine(prefix: String): String =
        "$prefix connectOk=${connectOk.get()} connectFailed=${connectFailed.get()} loginOk=${loginOk.get()} " +
            "loginFailed=${loginFailed.get()} cmd=${commandSent.get()} churn=${intentionalDisconnects.get()} dropped=${droppedConnections.get()}"

    fun summary(
        config: SwarmConfig,
        runtimeMs: Long,
    ): String {
        val connectPct = percentage(connectOk.get(), connectOk.get() + connectFailed.get())
        val loginPct = percentage(loginOk.get(), loginOk.get() + loginFailed.get())
        val connectStats = stats(connectLatenciesMs)
        val loginStats = stats(loginLatenciesMs)

        return buildString {
            appendLine("=== Swarm Load Test Summary ===")
            appendLine("bots=${config.run.totalBots}, runtimeMs=$runtimeMs, telnet%=${config.run.protocolMix.telnetPercent}")
            appendLine("connections: ok=${connectOk.get()} failed=${connectFailed.get()} successRate=${"%.2f".format(connectPct)}%")
            appendLine("logins: ok=${loginOk.get()} failed=${loginFailed.get()} successRate=${"%.2f".format(loginPct)}%")
            appendLine(
                "commandsSent=${commandSent.get()} intentionalDisconnects=${intentionalDisconnects.get()} dropped=${droppedConnections.get()}",
            )
            appendLine("connectLatencyMs: p50=${connectStats.p50} p95=${connectStats.p95} p99=${connectStats.p99} avg=${connectStats.avg}")
            appendLine("loginLatencyMs: p50=${loginStats.p50} p95=${loginStats.p95} p99=${loginStats.p99} avg=${loginStats.avg}")
        }
    }

    private fun percentage(
        numerator: Long,
        denominator: Long,
    ): Double {
        if (denominator == 0L) return 0.0
        return numerator * 100.0 / denominator
    }

    private fun stats(values: List<Long>): Stat {
        if (values.isEmpty()) return Stat(0, 0, 0, 0)
        val sorted = values.sorted()
        val avg = sorted.average().toLong()
        return Stat(
            percentile(sorted, 50),
            percentile(sorted, 95),
            percentile(sorted, 99),
            avg,
        )
    }

    private fun percentile(
        values: List<Long>,
        p: Int,
    ): Long {
        if (values.isEmpty()) return 0
        val rank = ((p / 100.0) * (values.size - 1)).toInt()
        return values[rank]
    }

    private data class Stat(
        val p50: Long,
        val p95: Long,
        val p99: Long,
        val avg: Long,
    )
}
