package dev.ambon.test

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class MutableClock(
    private var nowMs: Long,
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    fun advance(ms: Long) {
        nowMs += ms
    }

    fun set(ms: Long) {
        nowMs = ms
    }

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(nowMs, zone)

    override fun instant(): Instant = Instant.ofEpochMilli(nowMs)

    override fun millis(): Long = nowMs
}
