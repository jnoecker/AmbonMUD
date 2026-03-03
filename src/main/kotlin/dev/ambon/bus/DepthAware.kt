package dev.ambon.bus

/** Capability trait for bus implementations that can report queue depth and capacity. */
interface DepthAware {
    fun depth(): Int

    val capacity: Int
}
