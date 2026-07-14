package dev.ambon.domain.world

/**
 * Ride characteristics of a purchasable mount, authored on the shop item that
 * sells it (see `ItemFile.mountSpeed`/`flying`) and keyed by mount id.
 */
data class MountStats(
    /** Ride-speed multiplier over the configured base pace (1.0 = base). */
    val speed: Double,
    /** True when the mount can fly: cross-zone travel to explored rooms. */
    val flying: Boolean,
)
