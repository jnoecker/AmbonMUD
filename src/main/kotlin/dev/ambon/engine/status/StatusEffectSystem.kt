package dev.ambon.engine.status

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import java.time.Clock
import java.util.Random

class StatusEffectSystem(
    private val registry: StatusEffectRegistry,
    private val players: PlayerRegistry,
    private val mobs: MobRegistry,
    private val outbound: OutboundBus,
    private val clock: Clock,
    private val rng: Random = Random(),
    private val markVitalsDirty: (SessionId) -> Unit = {},
    private val markMobHpDirty: (MobId) -> Unit = {},
    private val markStatusDirty: (SessionId) -> Unit = {},
) {
    private val playerEffects = mutableMapOf<SessionId, MutableList<ActiveEffect>>()
    private val mobEffects = mutableMapOf<MobId, MutableList<ActiveEffect>>()

    // ── Apply ──────────────────────────────────────────────────────────

    fun applyToPlayer(
        sessionId: SessionId,
        effectId: StatusEffectId,
        sourceSessionId: SessionId? = null,
    ): Boolean {
        val def = registry.get(effectId) ?: return false
        val now = clock.millis()
        val list = playerEffects.getOrPut(sessionId) { mutableListOf() }
        return applyEffect(list, def, now, sourceSessionId)
    }

    fun applyToMob(
        mobId: MobId,
        effectId: StatusEffectId,
        sourceSessionId: SessionId? = null,
    ): Boolean {
        val def = registry.get(effectId) ?: return false
        val now = clock.millis()
        val list = mobEffects.getOrPut(mobId) { mutableListOf() }
        return applyEffect(list, def, now, sourceSessionId)
    }

    private fun applyEffect(
        list: MutableList<ActiveEffect>,
        def: StatusEffectDefinition,
        now: Long,
        sourceSessionId: SessionId?,
    ): Boolean {
        val existing = list.filter { it.definitionId == def.id }
        when (def.stackBehavior) {
            StackBehavior.REFRESH -> {
                val active = existing.firstOrNull()
                if (active != null) {
                    active.expiresAtMs = now + def.durationMs
                    active.lastTickAtMs = now
                    return true
                }
            }
            StackBehavior.STACK -> {
                if (existing.size >= def.maxStacks) {
                    // Refresh the oldest stack's duration instead of adding a new one
                    val oldest = existing.minByOrNull { it.appliedAtMs }
                    oldest?.expiresAtMs = now + def.durationMs
                    return true
                }
            }
            StackBehavior.NONE -> {
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
            ),
        )
        return true
    }

    // ── Tick ───────────────────────────────────────────────────────────

    suspend fun tick(nowMs: Long) {
        tickPlayerEffects(nowMs)
        tickMobEffects(nowMs)
    }

    private suspend fun tickPlayerEffects(nowMs: Long) {
        val itr = playerEffects.iterator()
        while (itr.hasNext()) {
            val (sessionId, effects) = itr.next()
            val player = players.get(sessionId)
            if (player == null) {
                itr.remove()
                continue
            }
            val effectItr = effects.iterator()
            while (effectItr.hasNext()) {
                val effect = effectItr.next()
                val def = registry.get(effect.definitionId)
                if (def == null || nowMs > effect.expiresAtMs) {
                    effectItr.remove()
                    if (def != null) {
                        outbound.send(
                            OutboundEvent.SendText(sessionId, "${def.displayName} fades."),
                        )
                    }
                    markStatusDirty(sessionId)
                    continue
                }
                // Depleted shields
                if (def.effectType == EffectType.SHIELD && effect.shieldRemaining <= 0) {
                    effectItr.remove()
                    outbound.send(
                        OutboundEvent.SendText(sessionId, "${def.displayName} shatters!"),
                    )
                    markStatusDirty(sessionId)
                    continue
                }
                // Tick DOT/HOT (fire the last tick when nowMs == expiresAtMs)
                if (def.tickIntervalMs > 0 && nowMs - effect.lastTickAtMs >= def.tickIntervalMs) {
                    effect.lastTickAtMs = nowMs
                    val value = rollRange(def.tickMinValue, def.tickMaxValue)
                    when (def.effectType) {
                        EffectType.DOT -> {
                            player.hp = (player.hp - value).coerceAtLeast(0)
                            markVitalsDirty(sessionId)
                            outbound.send(
                                OutboundEvent.SendText(
                                    sessionId,
                                    "${def.displayName} burns you for $value damage.",
                                ),
                            )
                        }
                        EffectType.HOT -> {
                            val before = player.hp
                            player.hp = (player.hp + value).coerceAtMost(player.maxHp)
                            val healed = player.hp - before
                            if (healed > 0) {
                                markVitalsDirty(sessionId)
                                outbound.send(
                                    OutboundEvent.SendText(
                                        sessionId,
                                        "${def.displayName} heals you for $healed HP.",
                                    ),
                                )
                            }
                        }
                        else -> {} // non-ticking types
                    }
                }
            }
            if (effects.isEmpty()) itr.remove()
        }
    }

    private suspend fun tickMobEffects(nowMs: Long) {
        val itr = mobEffects.iterator()
        while (itr.hasNext()) {
            val (mobId, effects) = itr.next()
            val mob = mobs.get(mobId)
            if (mob == null) {
                itr.remove()
                continue
            }
            val effectItr = effects.iterator()
            while (effectItr.hasNext()) {
                val effect = effectItr.next()
                val def = registry.get(effect.definitionId)
                if (def == null || nowMs > effect.expiresAtMs) {
                    effectItr.remove()
                    continue
                }
                // Tick DOT on mobs
                if (def.effectType == EffectType.DOT &&
                    def.tickIntervalMs > 0 &&
                    nowMs - effect.lastTickAtMs >= def.tickIntervalMs
                ) {
                    effect.lastTickAtMs = nowMs
                    val value = rollRange(def.tickMinValue, def.tickMaxValue)
                    mob.hp = (mob.hp - value).coerceAtLeast(0)
                    markMobHpDirty(mobId)
                    // Notify the player who applied it
                    val source = effect.sourceSessionId
                    if (source != null) {
                        outbound.send(
                            OutboundEvent.SendText(
                                source,
                                "${def.displayName} burns ${mob.name} for $value damage.",
                            ),
                        )
                    }
                }
            }
            if (effects.isEmpty()) itr.remove()
        }
    }

    // ── Queries ────────────────────────────────────────────────────────

    fun hasPlayerEffect(
        sessionId: SessionId,
        effectType: EffectType,
    ): Boolean = playerEffects[sessionId]?.any { registry.get(it.definitionId)?.effectType == effectType } == true

    fun hasMobEffect(
        mobId: MobId,
        effectType: EffectType,
    ): Boolean = mobEffects[mobId]?.any { registry.get(it.definitionId)?.effectType == effectType } == true

    fun getPlayerStatMods(sessionId: SessionId): StatModifiers {
        val effects = playerEffects[sessionId] ?: return StatModifiers.ZERO
        var result = StatModifiers.ZERO
        for (effect in effects) {
            val def = registry.get(effect.definitionId) ?: continue
            if (def.effectType == EffectType.STAT_BUFF || def.effectType == EffectType.STAT_DEBUFF) {
                result = result + def.statMods
            }
        }
        return result
    }

    fun getMobStatMods(mobId: MobId): StatModifiers {
        val effects = mobEffects[mobId] ?: return StatModifiers.ZERO
        var result = StatModifiers.ZERO
        for (effect in effects) {
            val def = registry.get(effect.definitionId) ?: continue
            if (def.effectType == EffectType.STAT_BUFF || def.effectType == EffectType.STAT_DEBUFF) {
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
            if (def.effectType != EffectType.SHIELD) continue
            val absorbed = remaining.coerceAtMost(effect.shieldRemaining)
            effect.shieldRemaining -= absorbed
            remaining -= absorbed
        }
        if (remaining != rawDamage) markStatusDirty(sessionId)
        return remaining
    }

    fun activePlayerEffects(sessionId: SessionId): List<ActiveEffectSnapshot> {
        val now = clock.millis()
        val effects = playerEffects[sessionId] ?: return emptyList()
        return effects
            .mapNotNull { effect ->
                val def = registry.get(effect.definitionId) ?: return@mapNotNull null
                ActiveEffectSnapshot(
                    id = def.id.value,
                    name = def.displayName,
                    type = def.effectType.name,
                    remainingMs = (effect.expiresAtMs - now).coerceAtLeast(0),
                    stacks = countStacks(playerEffects[sessionId], def.id),
                )
            }.distinctBy { it.id }
    }

    fun activeMobEffects(mobId: MobId): List<ActiveEffectSnapshot> {
        val now = clock.millis()
        val effects = mobEffects[mobId] ?: return emptyList()
        return effects
            .mapNotNull { effect ->
                val def = registry.get(effect.definitionId) ?: return@mapNotNull null
                ActiveEffectSnapshot(
                    id = def.id.value,
                    name = def.displayName,
                    type = def.effectType.name,
                    remainingMs = (effect.expiresAtMs - now).coerceAtLeast(0),
                    stacks = countStacks(mobEffects[mobId], def.id),
                )
            }.distinctBy { it.id }
    }

    private fun countStacks(
        list: List<ActiveEffect>?,
        defId: StatusEffectId,
    ): Int = list?.count { it.definitionId == defId } ?: 0

    // ── Cleanup ────────────────────────────────────────────────────────

    fun onPlayerDisconnected(sessionId: SessionId) {
        playerEffects.remove(sessionId)
    }

    fun onMobRemoved(mobId: MobId) {
        mobEffects.remove(mobId)
    }

    fun removeAllFromPlayer(sessionId: SessionId) {
        playerEffects.remove(sessionId)
        markStatusDirty(sessionId)
    }

    fun removeAllFromMob(mobId: MobId) {
        mobEffects.remove(mobId)
    }

    fun remapSession(
        oldSid: SessionId,
        newSid: SessionId,
    ) {
        playerEffects.remove(oldSid)?.let { playerEffects[newSid] = it }
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

    private fun rollRange(
        min: Int,
        max: Int,
    ): Int {
        if (max <= min) return min
        return min + rng.nextInt((max - min) + 1)
    }
}
