package dev.ambon.balance

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

import dev.ambon.config.AppConfig
import dev.ambon.config.AppConfigLoader
import dev.ambon.config.MobTierConfig
import dev.ambon.config.QuestDifficulty
import dev.ambon.domain.StatMap
import dev.ambon.domain.world.resolveMobStats
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerState
import dev.ambon.engine.expectedPlayerMeleeDamage
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * Tier-0 golden-fixture parity gate (Ambon world repo, gameplay_balance_plan.md
 * Phase 1): recomputes every value in the world repo's Tier-0 baseline fixtures
 * through the real engine code paths and fails on any mismatch.
 *
 * Opt-in: set TIER0_FIXTURES to the world repo's balance/fixtures/tier0
 * directory (containing resolved-values.json and encounter-grid.json).
 * Without it the test is skipped, so normal CI is unaffected.
 *
 * Config comes through AppConfigLoader's own deployment mechanism: stage the
 * effective config export as application-local.yaml in a directory and set
 * AMBONMUD_DATA_DIR to that directory - the loader then REPLACES the base
 * classpath application.yaml with it entirely, exactly as production does
 * with /app/data/application-local.yaml. (Do not layer the export as an
 * extra source: Hoplite deep-merges map keys across sources, producing a
 * base+overlay chimera no deployment ever runs - e.g. both the generic
 * 'chest' slot and the world's 'body' slot at order 2, which config
 * validation rightly rejects.)
 *
 * Example:
 *   AMBONMUD_DATA_DIR=/dir/containing/the/export \
 *   TIER0_FIXTURES=C:/Ambon/balance/fixtures/tier0 \
 *   ./gradlew test --tests dev.ambon.balance.Tier0ParityTest -x buildWeb
 *
 * Engine paths exercised: AppConfigLoader (Hoplite binding), PlayerProgression
 * (XP curve, quest XP, resource pools, level-gap and charisma multipliers),
 * resolveMobStats (tier scaling), expectedPlayerMeleeDamage and the armor/
 * dodge expectation composed from the same bindings. The fixtures' doubles are
 * serialized rounded to 6 decimals, so doubles compare with |delta| <= 1.5e-6;
 * integers compare exactly.
 */
class Tier0ParityTest {
    private val mapper = ObjectMapper()

    private data class Mismatch(val where: String, val fixture: Any?, val engine: Any?)

    @Test
    fun tier0FixturesMatchEngine() {
        val fixtureDir = System.getenv("TIER0_FIXTURES")
        assumeTrue(fixtureDir != null, "TIER0_FIXTURES not set; skipping parity gate")

        val config = AppConfigLoader.load()
        val progression = PlayerProgression(config = config.progression, bindings = config.engine.stats.bindings)
        val mismatches = mutableListOf<Mismatch>()

        val resolved = mapper.readTree(File(fixtureDir, "resolved-values.json"))
        checkResolvedValues(resolved, config, progression, mismatches)

        val grid = mapper.readTree(File(fixtureDir, "encounter-grid.json"))
        checkEncounterGrid(grid, config, progression, mismatches)

        val report = mismatches.take(50).joinToString("\n") { "${it.where}: fixture=${it.fixture} engine=${it.engine}" }
        check(mismatches.isEmpty()) {
            "${mismatches.size} parity mismatches (first 50):\n$report"
        }
        println("Tier-0 parity gate: all values match the engine.")
    }

    // --- resolved-values.json ------------------------------------------------

    private fun checkResolvedValues(
        root: JsonNode,
        config: AppConfig,
        progression: PlayerProgression,
        out: MutableList<Mismatch>,
    ) {
        // XP thresholds and kills-to-level inputs.
        root["xp"].fields().forEach { (levelStr, row) ->
            val level = levelStr.toInt()
            val total = progression.totalXpForLevel(level)
            if (total != row["totalXp"].asLong()) {
                out += Mismatch("xp[$level].totalXp", row["totalXp"].asLong(), total)
            }
            if (!row["xpToNext"].isNull) {
                val next = progression.totalXpForLevel(level + 1) - total
                if (next != row["xpToNext"].asLong()) {
                    out += Mismatch("xp[$level].xpToNext", row["xpToNext"].asLong(), next)
                }
            }
        }

        // Quest XP by difficulty tier.
        root["questXp"].fields().forEach { (tierName, byLevel) ->
            val difficulty = QuestDifficulty.parse(tierName)
            byLevel.fields().forEach { (levelStr, value) ->
                val engine = progression.computeQuestXp(difficulty, levelStr.toInt())
                if (engine != value.asLong()) {
                    out += Mismatch("questXp.$tierName[$levelStr]", value.asLong(), engine)
                }
            }
        }

        // Mob tier values through resolveMobStats.
        root["mobs"].fields().forEach { (tierName, byLevel) ->
            val tier = tierConfig(config, tierName)
            byLevel.fields().forEach { (levelStr, row) ->
                val ms = resolveMobStats(tier, levelStr.toInt())
                fun cmp(field: String, engine: Long) {
                    if (engine != row[field].asLong()) {
                        out += Mismatch("mobs.$tierName[$levelStr].$field", row[field].asLong(), engine)
                    }
                }
                cmp("hp", ms.hp.toLong())
                cmp("minDamage", ms.damage.min.toLong())
                cmp("maxDamage", ms.damage.max.toLong())
                cmp("armor", ms.armor.toLong())
                cmp("xpReward", ms.xpReward)
                cmp("goldMin", ms.goldMin)
                cmp("goldMax", ms.goldMax)
            }
        }

        // Player pools and naked melee EV per class.
        root["players"].fields().forEach { (classId, node) ->
            val def = config.engine.classes.definitions[classId]
                ?: config.engine.classes.definitions.entries.firstOrNull { it.key.equals(classId, ignoreCase = true) }?.value
            val hpRate = def?.hpScalingRate ?: config.progression.rewards.hpScalingRate
            val manaRate = def?.manaScalingRate ?: config.progression.rewards.manaScalingRate
            node["byLevel"].fields().forEach { (levelStr, row) ->
                val level = levelStr.toInt()
                val hp = progression.maxHpForLevel(level, PlayerState.BASE_STAT, hpRate)
                val mana = progression.maxManaForLevel(level, PlayerState.BASE_STAT, manaRate)
                if (hp.toLong() != row["maxHp"].asLong()) {
                    out += Mismatch("players.$classId[$levelStr].maxHp", row["maxHp"].asLong(), hp)
                }
                if (mana.toLong() != row["maxMana"].asLong()) {
                    out += Mismatch("players.$classId[$levelStr].maxMana", row["maxMana"].asLong(), mana)
                }
                val melee = expectedPlayerMeleeDamage(
                    bindings = config.engine.stats.bindings,
                    level = level,
                    stats = StatMap.of(config.engine.stats.bindings.meleeDamageStat to PlayerState.BASE_STAT),
                    equipAttack = 0,
                    enemyArmor = 0,
                )
                cmpDouble(out, "players.$classId[$levelStr].nakedMeleeEvVs0Armor", row["nakedMeleeEvVs0Armor"], melee)
            }
        }

        // Level-gap multipliers.
        root["levelGapMultipliers"]["diminishing"].fields().forEach { (n, v) ->
            val engine = progression.diminishingKillXpMultiplier(playerLevel = 1 + n.toInt(), mobLevel = 1)
            if (abs(engine - v.asDouble()) > EPS) {
                out += Mismatch("levelGapMultipliers.diminishing[$n]", v.asDouble(), engine)
            }
        }
        root["levelGapMultipliers"]["punchUp"].fields().forEach { (n, v) ->
            val engine = progression.underLevelKillXpMultiplier(playerLevel = 1, mobLevel = 1 + n.toInt())
            if (abs(engine - v.asDouble()) > EPS) {
                out += Mismatch("levelGapMultipliers.punchUp[$n]", v.asDouble(), engine)
            }
        }
    }

    // --- encounter-grid.json -------------------------------------------------

    private fun checkEncounterGrid(
        root: JsonNode,
        config: AppConfig,
        progression: PlayerProgression,
        out: MutableList<Mismatch>,
    ) {
        val bindings = config.engine.stats.bindings
        root["cells"].forEachIndexed { i, cell ->
            val player = cell["player"]
            val classId = player["class"].asText()
            val level = player["level"].asInt()
            val stats = player["stats"]
            val statMap = StatMap.of(
                *stats.fields().asSequence().map { (k, v) -> k to v.asInt() }.toList().toTypedArray(),
            )
            val equipAttack = cell["loadout"]["equipAttack"].asInt()
            val equipArmor = cell["loadout"]["equipArmor"].asInt()
            val mob = cell["mob"]
            val tier = tierConfig(config, mob["tier"].asText())
            val mobLevel = mob["level"].asInt()
            val ms = resolveMobStats(tier, mobLevel)
            val where = "cell[$i](${classId}/L$level/${player["gear"].asText()} vs ${mob["tier"].asText()}/L$mobLevel)"

            // Vitals from BASE stats (the engine never merges equipment stats into pools).
            val def = config.engine.classes.definitions[classId]
            val hpRate = def?.hpScalingRate ?: config.progression.rewards.hpScalingRate
            val manaRate = def?.manaScalingRate ?: config.progression.rewards.manaScalingRate
            val maxHp = progression.maxHpForLevel(level, PlayerState.BASE_STAT, hpRate)
            if (maxHp.toLong() != player["maxHp"].asLong()) {
                out += Mismatch("$where.maxHp", player["maxHp"].asLong(), maxHp)
            }
            val maxMana = progression.maxManaForLevel(level, PlayerState.BASE_STAT, manaRate)
            if (maxMana.toLong() != player["maxMana"].asLong()) {
                out += Mismatch("$where.maxMana", player["maxMana"].asLong(), maxMana)
            }

            // Player EV damage through the engine's own expectation function.
            val pDmg = expectedPlayerMeleeDamage(bindings, level, statMap, equipAttack, ms.armor)
            cmpDouble(out, "$where.playerExpectedDamagePerAction", cell["playerExpectedDamagePerAction"], pDmg)

            // Mob EV hit: midpoint roll, dodge EV, symmetric mitigation - composed
            // from the same bindings the real swing path uses (CombatSystem.kt:1456
            // applies mitigation; consider's EV omits it, a recorded quirk).
            val dodge = ((statMap[bindings.dodgeStat] - PlayerState.BASE_STAT) * bindings.dodgePerPoint)
                .coerceIn(0, bindings.maxDodgePercent)
            val mid = (ms.damage.min + ms.damage.max) / 2.0
            val afterDodge = mid * (1.0 - dodge / 100.0)
            val mitigation =
                if (equipArmor <= 0) 0.0 else equipArmor.toDouble() / (equipArmor + bindings.meleeArmorMitigationK)
            val mDmg = (afterDodge * (1.0 - mitigation)).coerceAtLeast(1.0)
            cmpDouble(out, "$where.mobExpectedDamagePerHit", cell["mobExpectedDamagePerHit"], mDmg)

            // Derived outcome fields.
            val actions = ceil(ms.hp / pDmg).toInt().coerceAtLeast(1)
            if (actions != cell["actionsToKill"].asInt()) {
                out += Mismatch("$where.actionsToKill", cell["actionsToKill"].asInt(), actions)
            }
            val hitsToDie = ceil(maxHp / mDmg).toInt().coerceAtLeast(1)
            if (hitsToDie != cell["mobHitsToKillPlayer"].asInt()) {
                out += Mismatch("$where.mobHitsToKillPlayer", cell["mobHitsToKillPlayer"].asInt(), hitsToDie)
            }
            val hpLost = min((actions - 1) * mDmg, maxHp.toDouble()) / maxHp
            cmpDouble(out, "$where.expectedHpLostFraction", cell["expectedHpLostFraction"], hpLost)
            val winPct = (hitsToDie.toDouble() / (hitsToDie + actions) * 100.0).toInt().coerceIn(0, 100)
            if (winPct != cell["considerStyleWinPct"].asInt()) {
                out += Mismatch("$where.considerStyleWinPct", cell["considerStyleWinPct"].asInt(), winPct)
            }

            // Kill XP: level-gap multiplier (truncating) then charisma stage,
            // through the engine's own multiplier and bonus functions.
            val mult = progression.diminishingKillXpMultiplier(level, mobLevel) *
                progression.underLevelKillXpMultiplier(level, mobLevel)
            val afterLevel =
                if (mult == 1.0) ms.xpReward else (ms.xpReward * mult).toLong().coerceAtLeast(0L)
            val killXp =
                if (afterLevel > 0) progression.applyCharismaXpBonus(statMap[bindings.xpBonusStat], afterLevel) else 0L
            if (killXp != cell["killXp"].asLong()) {
                out += Mismatch("$where.killXp", cell["killXp"].asLong(), killXp)
            }
        }
    }

    private fun tierConfig(config: AppConfig, name: String): MobTierConfig =
        when (name) {
            "weak" -> config.engine.mob.tiers.weak
            "standard" -> config.engine.mob.tiers.standard
            "elite" -> config.engine.mob.tiers.elite
            "boss" -> config.engine.mob.tiers.boss
            else -> error("unknown tier $name")
        }

    private fun cmpDouble(out: MutableList<Mismatch>, where: String, fixture: JsonNode, engine: Double) {
        if (abs(engine - fixture.asDouble()) > EPS) {
            out += Mismatch(where, fixture.asDouble(), engine)
        }
    }

    private companion object {
        // Fixture doubles are serialized rounded to 6 decimals.
        const val EPS = 1.5e-6
    }
}
