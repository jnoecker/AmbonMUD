package dev.ambon.engine.status

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.StatBlock
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.DirtyNotifier
import dev.ambon.engine.GameSystem
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.healHp
import dev.ambon.engine.remapKey
import dev.ambon.engine.rollRange
import dev.ambon.engine.takeDamage
import java.time.Clock
import java.util.Random

class StatusEffectSystem(
    private val registry: StatusEffectRegistry,
    private val players: PlayerRegistry,
    private val mobs: MobRegistry,
    private val outbound: OutboundBus,
    private val clock: Clock,
    private val rng: Random = Random(),
    private val dirtyNotifier: DirtyNotifier = DirtyNotifier.NO_OP,
) : GameSystem {
    private val playerEffects = mutableMapOf<SessionId, MutableList<ActiveEffect>>()
    private val mobEffects = mutableMapOf<MobId, MutableList<ActiveEffect>>()

    // ── Apply ──────────────────────────────────────────────────────────

    fun applyToPlayer(
        sessionId: SessionId,
        effectId: StatusEffectId,
        sourceSessionId: SessionId? = null,
    ): Boolean = applyTo(playerEffects, sessionId, effectId, sourceSessionId)

    fun applyToMob(
        mobId: MobId,
        effectId: StatusEffectId,
        sourceSessionId: SessionId? = null,
    ): Boolean = applyTo(mobEffects, mobId, effectId, sourceSessionId)

    private fun <K> applyTo(
        map: MutableMap<K, MutableList<ActiveEffect>>,
        key: K,
        effectId: StatusEffectId,
        sourceSessionId: SessionId?,
    ): Boolean {
        val def = registry.get(effectId) ?: return false
        val now = clock.millis()
        val list = map.getOrPut(key) { mutableListOf() }
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
                // Depleted shields — remove immediately
                if (def.effectType == EffectType.SHIELD && effect.shieldRemaining <= 0) {
                    outbound.send(OutboundEvent.SendText(sessionId, "${def.displayName} shatters!"))
                    dirtyNotifier.playerStatusDirty(sessionId)
                    return@tickEffects true
                }
                // Tick DOT/HOT
                if (def.tickIntervalMs > 0 && nowMs - effect.lastTickAtMs >= def.tickIntervalMs) {
                    effect.lastTickAtMs = nowMs
                    val value = rollRange(rng, def.tickMinValue, def.tickMaxValue)
                    when (def.effectType) {
                        EffectType.DOT -> {
                            player.takeDamage(value)
                            dirtyNotifier.playerVitalsDirty(sessionId)
                            outbound.send(
                                OutboundEvent.SendText(
                                    sessionId,
                                    "${def.displayName} burns you for $value damage.",
                                ),
                            )
                        }
                        EffectType.HOT -> {
                            val before = player.hp
                            player.healHp(value)
                            val healed = player.hp - before
                            if (healed > 0) {
                                dirtyNotifier.playerVitalsDirty(sessionId)
                                outbound.send(
                                    OutboundEvent.SendText(
                                        sessionId,
                                        "${def.displayName} heals you for $healed HP.",
                                    ),
                                )
                            }
                        }
                        else -> {}
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
            onExpired = { _, _ -> },
            onActive = { mobId, mob, effect, def ->
                if (def.effectType == EffectType.DOT &&
                    def.tickIntervalMs > 0 &&
                    nowMs - effect.lastTickAtMs >= def.tickIntervalMs
                ) {
                    effect.lastTickAtMs = nowMs
                    val value = rollRange(rng, def.tickMinValue, def.tickMaxValue)
                    mob.takeDamage(value)
                    dirtyNotifier.mobHpDirty(mobId)
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
                false // keep the effect
            },
        )
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

    fun getPlayerStatMods(sessionId: SessionId): StatBlock = computeStatMods(playerEffects[sessionId])

    fun getMobStatMods(mobId: MobId): StatBlock = computeStatMods(mobEffects[mobId])

    private fun computeStatMods(effects: List<ActiveEffect>?): StatBlock {
        val activeEffects = effects ?: return StatBlock.ZERO
        var result = StatBlock.ZERO
        for (effect in activeEffects) {
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
                    type = def.effectType.name,
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
        mobEffects.remove(mobId)
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
