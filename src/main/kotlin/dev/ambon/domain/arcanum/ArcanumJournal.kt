package dev.ambon.domain.arcanum

/**
 * A player's personal Arcanum — the illuminated journal kept by those who have
 * taken the Akathavae pledge. Records every room visited, creature illuminated,
 * and item discovered, with first-recorded timestamps for world-first credit.
 *
 * Serialized as a JSON blob into [dev.ambon.persistence.PlayerRecord.arcanumData],
 * mirroring how daily-quest state is persisted.
 */
data class ArcanumJournal(
    /** Creature entries, keyed by mob template key (`zone:template`). */
    var mobs: MutableMap<String, ArcanumEntry> = mutableMapOf(),
    /** Item entries, keyed by item template id. */
    var items: MutableMap<String, ArcanumEntry> = mutableMapOf(),
    /** Room entries, keyed by full `zone:room` id. */
    var rooms: MutableMap<String, ArcanumEntry> = mutableMapOf(),
    /**
     * Zones whose completion bundle (XP + gold) has already been paid — each
     * zone rewards exactly once, ever. Defaulted so blobs written before this
     * field existed still deserialize.
     */
    var completedZones: MutableSet<String> = mutableSetOf(),
)

/** One recorded subject in a player's [ArcanumJournal]. */
data class ArcanumEntry(
    /** Epoch-ms when this subject was first recorded by this player. */
    var firstRecordedAtMs: Long = 0L,
    /** How many times this subject has been recorded (first + repeats). */
    var timesRecorded: Int = 1,
    /** Epoch-ms when this entry last yielded XP — drives the repeat-XP cooldown. */
    var lastXpAtMs: Long = 0L,
    /** How the subject entered the journal (see [ArcanumSource]). */
    var source: String = ArcanumSource.ILLUMINATED,
)

/** How a subject entered the journal. Stored as a string for forward compatibility. */
object ArcanumSource {
    const val ILLUMINATED = "illuminated"
    const val VISITED = "visited"
    const val OBSERVED = "observed"
    const val PURCHASED = "purchased"
    const val CRAFTED = "crafted"
    const val GATHERED = "gathered"
}
