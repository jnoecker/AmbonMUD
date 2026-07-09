package dev.ambon.engine

import dev.ambon.config.LeaderboardConfig
import dev.ambon.domain.arcanum.ArcanumJournal
import dev.ambon.persistence.PlayerRecord
import dev.ambon.persistence.PlayerRepository
import dev.ambon.persistence.jsonMapper
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Maintains in-memory leaderboard caches for each category.
 * Refresh is triggered periodically (via the engine scheduler) and is safe to call from
 * suspend contexts — it dispatches to Dispatchers.IO via the repository.
 * Online players' live state is merged over their persisted records so that
 * in-session progress (kills, dungeon completions) is reflected immediately.
 */
class LeaderboardSystem(
    private val playerRepo: PlayerRepository,
    private val playerRegistry: PlayerRegistry,
    private val config: LeaderboardConfig = LeaderboardConfig(),
) {
    enum class Category(
        val key: String,
        val displayName: String,
        val scoreLabel: String,
    ) {
        LEVEL("level", "Highest Level", "level"),
        ACHIEVEMENTS("achievements", "Most Achievements", "achievements"),
        CRAFTING("crafting", "Best Crafter", "craft lvl"),
        DUNGEONS("dungeons", "Dungeon Champion", "dungeons"),
        KILLS("kills", "Monster Slayer", "kills"),
        PRESTIGE("prestige", "Most Prestigious", "prestige"),
        PVP_KILLS("pvp", "PvP Champion", "pvp kills"),
        CHRONICLER("arcanum", "Master Chronicler", "pages"),

        // TODO(#1391): add WORLD_FIRSTS("firsts", "Pioneer", "firsts") once the persisted
        //  `worldFirstsCount` field (PlayerRecord/PlayerState, column `world_firsts_count`)
        //  from the Akathavae achievement-criteria issue lands.
        ;

        companion object {
            fun fromKey(key: String): Category? =
                entries.firstOrNull { it.key == key || it.key.startsWith(key) }
        }
    }

    data class LeaderboardEntry(
        val rank: Int,
        val playerName: String,
        val score: Long,
    )

    @Volatile
    private var cache: Map<String, List<LeaderboardEntry>> = emptyMap()

    val refreshIntervalMs: Long get() = config.refreshIntervalMs

    /** Returns the top entries for [category], or an empty list if the cache is not yet populated. */
    fun getEntries(category: Category): List<LeaderboardEntry> = cache[category.key] ?: emptyList()

    /**
     * Rebuilds the leaderboard cache from persistent storage, overlaying live player state
     * for any currently-online players.
     */
    suspend fun refresh() {
        try {
            val allRecords = playerRepo.findAll().associateBy { it.id.value }.toMutableMap()

            // Overlay live in-memory state — online players may have unsaved kills or dungeon runs.
            for (live in playerRegistry.allPlayers()) {
                val id = live.playerId?.value ?: continue
                val base = allRecords[id] ?: continue
                allRecords[id] = base.copy(
                    level = live.level,
                    unlockedAchievementIds = live.unlockedAchievementIds,
                    craftingSkills = live.craftingSkills.toMap(),
                    mobsKilledTotal = live.mobsKilledTotal,
                    dungeonsCompleted = live.dungeonsCompleted,
                    prestigeLevel = live.prestigeLevel,
                    pvpKills = live.pvpKills,
                )
            }

            // Chronicler scores are computed in a side map: the Arcanum journal is persisted
            // as a JSON blob, so parse it per record instead of copying a journal back onto
            // the record overlay. Online players use their live in-memory journal instead.
            val chroniclerScores = mutableMapOf<Long, Long>()
            for (record in allRecords.values) {
                chroniclerScores[record.id.value] = arcanumPageCount(record.arcanumData)
            }
            for (live in playerRegistry.allPlayers()) {
                val id = live.playerId?.value ?: continue
                if (id !in allRecords) continue
                chroniclerScores[id] =
                    (live.arcanum.mobs.size + live.arcanum.items.size + live.arcanum.rooms.size).toLong()
            }

            val records = allRecords.values.toList()
            val topN = config.topN

            val newCache = mutableMapOf<String, List<LeaderboardEntry>>()
            newCache[Category.LEVEL.key] = rank(records, topN) { it.level.toLong() }
            newCache[Category.ACHIEVEMENTS.key] = rank(records, topN) { it.unlockedAchievementIds.size.toLong() }
            newCache[Category.CRAFTING.key] = rank(records, topN) { maxCraftLevel(it) }
            newCache[Category.DUNGEONS.key] = rank(records, topN) { it.dungeonsCompleted.toLong() }
            newCache[Category.KILLS.key] = rank(records, topN) { it.mobsKilledTotal }
            newCache[Category.PRESTIGE.key] = rank(records, topN) { it.prestigeLevel.toLong() }
            newCache[Category.PVP_KILLS.key] = rank(records, topN) { it.pvpKills.toLong() }
            newCache[Category.CHRONICLER.key] = rank(records, topN) { chroniclerScores[it.id.value] ?: 0L }
            cache = newCache

            log.debug { "Leaderboard refreshed — ${records.size} records, topN=$topN" }
        } catch (e: Exception) {
            log.warn(e) { "Leaderboard refresh failed" }
        }
    }

    private fun rank(
        records: List<PlayerRecord>,
        topN: Int,
        scoreOf: (PlayerRecord) -> Long,
    ): List<LeaderboardEntry> =
        records
            .filter { !it.isStaff }
            .sortedByDescending(scoreOf)
            .take(topN)
            .mapIndexed { i, r -> LeaderboardEntry(rank = i + 1, playerName = r.name, score = scoreOf(r)) }

    private fun maxCraftLevel(record: PlayerRecord): Long =
        record.craftingSkills.values.maxOfOrNull { it.level.toLong() } ?: 0L

    /** Total distinct Arcanum pages in a persisted journal blob. Blank/malformed blobs score 0. */
    private fun arcanumPageCount(arcanumData: String): Long {
        if (arcanumData.isBlank()) return 0L
        val journal = runCatching {
            jsonMapper.readValue(arcanumData, ArcanumJournal::class.java)
        }.getOrNull() ?: return 0L
        return (journal.mobs.size + journal.items.size + journal.rooms.size).toLong()
    }
}
