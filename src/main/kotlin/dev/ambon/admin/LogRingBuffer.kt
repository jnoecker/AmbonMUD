package dev.ambon.admin

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * A Logback appender that keeps the last [capacity] log entries in a ring buffer.
 * Entries can be retrieved via [entries] for the admin REST API.
 */
class LogRingBuffer : AppenderBase<ILoggingEvent>() {
    var capacity: Int = 2000

    private val buffer = ArrayDeque<LogEntry>()
    private val lock = Any()

    override fun append(eventObject: ILoggingEvent) {
        val entry = LogEntry(
            timestamp = Instant.ofEpochMilli(eventObject.timeStamp)
                .atOffset(ZoneOffset.UTC)
                .format(TIMESTAMP_FORMAT),
            epochMs = eventObject.timeStamp,
            level = eventObject.level.toString(),
            logger = eventObject.loggerName,
            message = eventObject.formattedMessage,
            thread = eventObject.threadName,
        )
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > capacity) {
                buffer.removeFirst()
            }
        }
    }

    /** Returns log entries, optionally filtered. Thread-safe snapshot. */
    fun entries(
        minLevel: Level? = null,
        sinceEpochMs: Long? = null,
        loggerPrefix: String? = null,
        limit: Int = capacity,
    ): List<LogEntry> {
        val snapshot = synchronized(lock) { buffer.toList() }
        var result: List<LogEntry> = snapshot
        if (sinceEpochMs != null) {
            result = result.filter { it.epochMs >= sinceEpochMs }
        }
        if (minLevel != null) {
            result = result.filter { Level.toLevel(it.level).isGreaterOrEqual(minLevel) }
        }
        if (loggerPrefix != null) {
            result = result.filter { it.logger.startsWith(loggerPrefix, ignoreCase = true) }
        }
        return result.takeLast(limit)
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
    }

    companion object {
        private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

        /** Global instance set by Logback during startup — used by the admin API. */
        @Volatile
        var instance: LogRingBuffer? = null
    }

    override fun start() {
        super.start()
        instance = this
    }
}

data class LogEntry(
    val timestamp: String,
    val epochMs: Long,
    val level: String,
    val logger: String,
    val message: String,
    val thread: String,
)
