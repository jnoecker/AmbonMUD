package dev.ambon.engine.status

import dev.ambon.bus.OutboundBus
import dev.ambon.config.EffectTypesConfig
import dev.ambon.config.StatBindingsConfig
import dev.ambon.domain.StatMap
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.DirtyNotifier
import dev.ambon.engine.GameSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.PlayerState
import dev.ambon.engine.applyHeal
import dev.ambon.engine.events.CombatEvent
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.remapKey
import dev.ambon.engine.rollRange
import dev.ambon.engine.takeDamage
import java.time.Clock
import java.util.Random
import kotlin.math.pow
import kotlin.math.roundToInt

class StatusEffectSystem(
    private val registry: StatusEffectRegistry,
    private val players: PlayerRegistry,
    private val mobs: MobRegistry,
    private val outbound: OutboundBus,
    private val clock: Clock,
    private val rng: Random = Random(),
    private val dirtyNotifier: DirtyNotifier = DirtyNotifier.NO_OP,
    private val effectTypes: EffectTypesConfig = EffectTypesConfig(),
    private val bindings: StatBindingsConfig = StatBindingsConfig(),
) : GameSystem {
    /** Callback for combat events (DOT/HOT ticks); wired by GameEngine after construction. */
    var onCombatEvent: suspend (SessionId, CombatEvent) -> Unit = { _, _ -> }
    private val playerEffects = mutableMapOf<SessionId, MutableList<ActiveEffect>>()
    private val mobEffects = mutableMapOf<MobId, MutableList<ActiveEffect>>()

    // ── Apply ──────────────────────────────────────────────────────────

    fun applyToPlayer(
        sessionId: SessionId,
        effectId: StatusEffectId,
        sourceSessionId: SessionId? = null,
        casterLevel: Int? = null,
        casterStats: StatMap? = null,
    ): Boolean = applyTo(playerEffects, sessionId, effectId, sourceSessionId, casterLevel, casterStats)

    fun applyToMob(
        mobId: MobId,
        effectId: StatusEffectId,
        sourceSessionId: SessionId? = null,
        casterLevel: Int? = null,
        casterStats: StatMap? = null,
    ): Boolean {
        val applied = applyTo(mobEffects, mobId, effectId, sourceSessionId, casterLevel, casterStats)
        if (applied) dirtyNotifier.mobHpDirty(mobId)
        return applied
    }

    private fun <K> applyTo(
        map: MutableMap<K, MutableList<ActiveEffect>>,
        key: K,
        effectId: StatusEffectId,
        sourceSessionId: SessionId?,
        casterLevel: Int?,
        casterStats: StatMap?,
    ): Boolean {
        val def = registry.get(effectId) ?: return false
        val now = clock.millis()
        val list = map.getOrPut(key) { mutableListOf() }
        return applyEffect(list, def, now, sourceSessionId, casterLevel, casterStats)
    }

    private fun applyEffect(
        list: MutableList<ActiveEffect>,
        def: StatusEffectDefinition,
        now: Long,
        sourceSessionId: SessionId?,
        casterLevel: Int?,
        casterStats: StatMap?,
    ): Boolean {
        val existing = list.filter { it.definitionId == def.id }
        val effectiveMaxStacks = def.maxStacks.coerceAtLeast(1)
        when (def.stackBehavior) {
            "refresh" -> {
                val active = existing.firstOrNull()
                if (active != null) {
                    active.expiresAtMs = now + def.durationMs
                    active.lastTickAtMs = now
                    return true
                }
            }
            "stack" -> {
                if (existing.size >= effectiveMaxStacks) {
                    // Refresh the oldest stack's duration instead of adding a new one
                    val oldest = existing.minByOrNull { it.appliedAtMs }
                    oldest?.expiresAtMs = now + def.durationMs
                    return true
                }
            }
            "none" -> {
                if (existing.isNotEmpty()) return false
            }
        }
        list.add(
            ActiveEffect(
                definitionId = def.id,
                appliedAtMs = now,
                expiresAtMs = now + def.durationMs,
                lastTickAtMs = now,
                sourceSessionId = sourceSessionId,
                shieldRemaining = def.shieldAmount,
                tickAnchor = computeTickAnchor(def, casterLevel, casterStats),
            ),
        )
        return true
    }

    /**
     * Snapshots a scaled per-tick anchor for damage/heal-over-time effects using
     * the same shape as direct spell/heal damage:
     *
     *   anchor     = (tickMinValue + tickMaxValue) / 2
     *   statBonus  = (stats[stat] - BASE_STAT) × statMultiplier
     *   levelScale = levelScalingRate ^ (level - 1)
     *   tickAnchor = (anchor + statBonus) × levelScale
     *
     * Variance is applied per-tick, not snapshotted, so each tick still rolls a
     * fresh spread. Returns null when the effect doesn't tick damage/healing,
     * when no caster context was provided, or when the authored range is zero
     * (nothing to scale) — in which case the tick loop falls back to rolling
     * the authored range directly.
     */
    private fun computeTickAnchor(
        def: StatusEffectDefinition,
        casterLevel: Int?,
        casterStats: StatMap?,
    ): Double? {
        if (casterLevel == null) return null
        if (def.tickIntervalMs <= 0L) return null
        val typeConfig = effectTypes.get(def.effectType) ?: return null
        val (statKey, statMul, rate) = when {
            typeConfig.ticksDamage -> Triple(bindings.spellDamageStat, bindings.spellStatMultiplier, bindings.spellLevelScalingRate)
            typeConfig.ticksHealing -> Triple(bindings.healStat, bindings.healStatMultiplier, bindings.healLevelScalingRate)
            else -> return null
        }
        val anchor = (def.tickMinValue + def.tickMaxValue) / 2.0
        val statTotal = casterStats?.get(statKey) ?: PlayerState.BASE_STAT
        val statBonus = (statTotal - PlayerState.BASE_STAT) * statMul
        val levelScale = rate.pow((casterLevel - 1).coerceAtLeast(0))
        return (anchor + statBonus) * levelScale
    }

    private fun rollTickValue(
        def: StatusEffectDefinition,
        anchor: Double?,
    ): Int {
        if (anchor == null) return rollRange(rng, def.tickMinValue, def.tickMaxValue)
        // Reuse the same variance window as direct spell damage so DOT/HOT
        // roll-to-roll spread matches a comparable direct spell.
        val varianceMin = bindings.spellVarianceMin
        val varianceMax = bindings.spellVarianceMax
        val span = varianceMax - varianceMin
        val variance = if (span <= 0.0) varianceMin else varianceMin + rng.nextDouble() * span
        return (anchor * variance).roundToInt().coerceAtLeast(1)
    }

    // ── Tick ───────────────────────────────────────────────────────────

    suspend fun tick(nowMs: Long) {
        tickPlayerEffects(nowMs)
        tickMobEffects(nowMs)
    }

    /**
     * Generic tick loop: iterates a map of entity-id → effects,
     * resolves each entity, prunes expired/orphaned effects, and
     * delegates active-effect handling to [onExpired] and [onActive].
     *
     * [onActive] returns `true` if the effect should be removed (e.g. depleted shield).
     */
    private inline fun <K, E> tickEffects(
        map: MutableMap<K, MutableList<ActiveEffect>>,
        nowMs: Long,
        resolve: (K) -> E?,
        onExpired: (K, StatusEffectDefinition) -> Unit,
        onActive: (K, E, ActiveEffect, StatusEffectDefinition) -> Boolean,
    ) {
        val itr = map.iterator()
        while (itr.hasNext()) {
            val (key, effects) = itr.next()
            val entity = resolve(key)
            if (entity == null) {
                itr.remove()
                continue
            }
            val effectItr = effects.iterator()
            while (effectItr.hasNext()) {
                val effect = effectItr.next()
                val def = registry.get(effect.definitionId)
                if (def == null || nowMs > effect.expiresAtMs) {
                    effectItr.remove()
                    if (def != null) onExpired(key, def)
                    continue
                }
                if (onActive(key, entity, effect, def)) {
                    effectItr.remove()
                }
            }
            if (effects.isEmpty()) itr.remove()
        }
    }

    private suspend fun tickPlayerEffects(nowMs: Long) {
        tickEffects(
            map = playerEffects,
            nowMs = nowMs,
            resolve = { players.get(it) },
            onExpired = { sessionId, def ->
                outbound.send(OutboundEvent.SendText(sessionId, "${def.displayName} fades."))
                dirtyNotifier.playerStatusDirty(sessionId)
            },
            onActive = { sessionId, player, effect, def ->
                val typeConfig = effectTypes.get(def.effectType)
                // Depleted shields — remove immediately
                if (typeConfig?.absorbsDamage == true && effect.shieldRemaining <= 0) {
                    outbound.send(OutboundEvent.SendText(sessionId, "${def.displayName} shatters!"))
                    dirtyNotifier.playerStatusDirty(sessionId)
                    return@tickEffects true
                }
                // Tick DOT/HOT
                if (def.tickIntervalMs > 0 && nowMs - effect.lastTickAtMs >= def.tickIntervalMs) {
                    effect.lastTickAtMs = nowMs
                    val value = rollTickValue(def, effect.tickAnchor)
                    if (typeConfig?.ticksDamage == true) {
                        player.takeDamage(value)
                        dirtyNotifier.playerVitalsDirty(sessionId)
                        val dotText = "${def.displayName} burns you for $value damage."
                        outbound.send(OutboundEvent.SendText(sessionId, dotText))
                        onCombatEvent(
                            sessionId,
                            CombatEvent.DotTick(
                                effectName = def.displayName,
                                targetName = player.name,
                                targetId = null,
                                damage = value,
                                text = dotText,
                            ),
                        )
                    } else if (typeConfig?.ticksHealing == true) {
                        val healed = applyHeal(sessionId, player, value, dirtyNotifier)
                        if (healed > 0) {
                            val hotText = "${def.displayName} heals you for $healed HP."
                            outbound.send(OutboundEvent.SendText(sessionId, hotText))
                            onCombatEvent(
                                sessionId,
                                CombatEvent.HotTick(
                                    effectName = def.displayName,
                                    targetName = player.name,
                                    amount = healed,
                                    text = hotText,
                                ),
                            )
                        }
                    }
                }
                false // keep the effect
            },
        )
    }

    private suspend fun tickMobEffects(nowMs: Long) {
        tickEffects(
            map = mobEffects,
            nowMs = nowMs,
            resolve = { mobs.get(it) },
            onExpired = { mobId, _ -> dirtyNotifier.mobHpDirty(mobId) },
            onActive = { mobId, mob, effect, def ->
                val typeConfig = effectTypes.get(def.effectType)
                if (typeConfig?.ticksDamage == true &&
                    def.tickIntervalMs > 0 &&
                    nowMs - effect.lastTickAtMs >= def.tickIntervalMs
                ) {
                    effect.lastTickAtMs = nowMs
                    val value = rollTickValue(def, effect.tickAnchor)
                    mob.takeDamage(value)
                    dirtyNotifier.mobHpDirty(mobId)
                    val source = effect.sourceSessionId
                    if (source != null) {
                        val dotText = "${def.displayName} burns ${mob.name} for $value damage."
                        outbound.send(OutboundEvent.SendText(source, dotText))
                        onCombatEvent(
                            source,
                            CombatEvent.DotTick(
                                effectName = def.displayName,
                                targetName = mob.name,
                                targetId = mobId.value,
                                damage = value,
                                text = dotText,
                            ),
                        )
                    }
                }
                false // keep the effect
            },
        )
    }

    // ── Queries ────────────────────────────────────────────────────────

    fun hasPlayerEffect(
        sessionId: SessionId,
        effectType: String,
    ): Boolean = playerEffects[sessionId]?.any { registry.get(it.definitionId)?.effectType == effectType } == true

    fun hasMobEffect(
        mobId: MobId,
        effectType: String,
    ): Boolean = mobEffects[mobId]?.any { registry.get(it.definitionId)?.effectType == effectType } == true

    fun getPlayerStatMods(sessionId: SessionId): StatMap = computeStatMods(playerEffects[sessionId])

    fun getMobStatMods(mobId: MobId): StatMap = computeStatMods(mobEffects[mobId])

    private fun computeStatMods(effects: List<ActiveEffect>?): StatMap {
        val activeEffects = effects ?: return StatMap.EMPTY
        var result = StatMap.EMPTY
        for (effect in activeEffects) {
            val def = registry.get(effect.definitionId) ?: continue
            if (effectTypes.get(def.effectType)?.modifiesStats == true) {
                result = result + def.statMods
            }
        }
        return result
    }

    /**
     * Absorb damage through active SHIELDs on a player. Returns the damage
     * remaining after absorption (may be 0 if fully absorbed).
     */
    fun absorbPlayerDamage(
        sessionId: SessionId,
        rawDamage: Int,
    ): Int {
        val effects = playerEffects[sessionId] ?: return rawDamage
        var remaining = rawDamage
        for (effect in effects) {
            if (remaining <= 0) break
            val def = registry.get(effect.definitionId) ?: continue
            if (effectTypes.get(def.effectType)?.absorbsDamage != true) continue
            val absorbed = remaining.coerceAtMost(effect.shieldRemaining)
            effect.shieldRemaining -= absorbed
            remaining -= absorbed
        }
        if (remaining != rawDamage) dirtyNotifier.playerStatusDirty(sessionId)
        return remaining
    }

    fun activePlayerEffects(sessionId: SessionId): List<ActiveEffectSnapshot> = snapshotEffects(playerEffects[sessionId], clock.millis())

    fun activeMobEffects(mobId: MobId): List<ActiveEffectSnapshot> = snapshotEffects(mobEffects[mobId], clock.millis())

    private fun snapshotEffects(
        effects: List<ActiveEffect>?,
        now: Long,
    ): List<ActiveEffectSnapshot> {
        val list = effects ?: return emptyList()
        return list
            .mapNotNull { effect ->
                val def = registry.get(effect.definitionId) ?: return@mapNotNull null
                ActiveEffectSnapshot(
                    id = def.id.value,
                    name = def.displayName,
                    type = def.effectType,
                    remainingMs = (effect.expiresAtMs - now).coerceAtLeast(0),
                    stacks = countStacks(list, def.id),
                )
            }.distinctBy { it.id }
    }

    private fun countStacks(
        list: List<ActiveEffect>?,
        defId: StatusEffectId,
    ): Int = list?.count { it.definitionId == defId } ?: 0

    // ── Cleanup ────────────────────────────────────────────────────────

    override suspend fun onPlayerDisconnected(sessionId: SessionId) {
        playerEffects.remove(sessionId)
    }

    fun onMobRemoved(mobId: MobId) {
        mobEffects.remove(mobId)
    }

    fun removeAllFromPlayer(sessionId: SessionId) {
        playerEffects.remove(sessionId)
        dirtyNotifier.playerStatusDirty(sessionId)
    }

    fun removeAllFromMob(mobId: MobId) {
        if (mobEffects.remove(mobId) != null) {
            dirtyNotifier.mobHpDirty(mobId)
        }
    }

    override fun remapSession(
        oldSid: SessionId,
        newSid: SessionId,
    ) {
        playerEffects.remapKey(oldSid, newSid)
    }

    /**
     * Returns mob IDs that have DOT effects and whose HP has reached 0
     * (killed by a tick). The caller is responsible for handling the death.
     * Credits the most recently applied effect's source (most likely the killer).
     */
    fun mobsKilledByDot(): List<Pair<MobId, SessionId?>> {
        val killed = mutableListOf<Pair<MobId, SessionId?>>()
        for ((mobId, effects) in mobEffects) {
            val mob = mobs.get(mobId) ?: continue
            if (mob.hp <= 0) {
                // Credit the most recently applied DOT source (likely the killing blow)
                val source =
                    effects
                        .filter { it.sourceSessionId != null }
                        .maxByOrNull { it.appliedAtMs }
                        ?.sourceSessionId
                        ?: effects.firstOrNull()?.sourceSessionId
                killed.add(mobId to source)
            }
        }
        return killed
    }
}
