package dev.ambon.engine.commands.handlers

import dev.ambon.domain.PlayerClass
import dev.ambon.domain.Race
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemSlot
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectSystem

class ProgressionHandler(
    router: CommandRouter,
    ctx: EngineContext,
    private val progression: PlayerProgression = PlayerProgression(),
    private val abilitySystem: AbilitySystem? = null,
    private val statusEffects: StatusEffectSystem? = null,
    private val groupSystem: GroupSystem? = null,
) {
    private val players = ctx.players
    private val items = ctx.items
    private val combat = ctx.combat
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    init {
        router.on<Command.Score> { sid, _ -> handleScore(sid) }
        router.on<Command.Spells> { sid, _ -> handleSpells(sid) }
        router.on<Command.Effects> { sid, _ -> handleEffects(sid) }
        router.on<Command.Balance> { sid, _ -> handleBalance(sid) }
    }

    private suspend fun handleScore(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val equipped = items.equipment(sessionId)

            val attackBonus = equipped.values.sumOf { it.item.damage }
            val dmgMin = combat.minDamage + attackBonus
            val dmgMax = combat.maxDamage + attackBonus

            val xpLine =
                run {
                    val into = progression.xpIntoLevel(me.xpTotal)
                    val span = progression.xpToNextLevel(me.xpTotal)
                    if (span == null) "MAXED" else "${"%,d".format(into)} / ${"%,d".format(span)}"
                }

            val armorTotal = equipped.values.sumOf { it.item.armor }
            val armorDetail =
                if (armorTotal > 0) {
                    val parts =
                        ItemSlot.entries
                            .filter { slot -> equipped[slot]?.item?.armor?.let { it > 0 } == true }
                            .joinToString(", ") { slot -> "${slot.label()}: ${equipped[slot]!!.item.displayName}" }
                    "+$armorTotal ($parts)"
                } else {
                    "+0"
                }

            val raceName = Race.fromString(me.race)?.displayName ?: me.race
            val className = PlayerClass.fromString(me.playerClass)?.displayName ?: me.playerClass

            outbound.send(OutboundEvent.SendInfo(sessionId, "[ ${me.name} — Level ${me.level} $raceName $className ]"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  HP  : ${me.hp} / ${me.maxHp}      XP : $xpLine"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Mana: ${me.mana} / ${me.maxMana}"))
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  STR: ${formatStat(me.strength, equipped.values.sumOf { it.item.strength })}  " +
                        "DEX: ${formatStat(me.dexterity, equipped.values.sumOf { it.item.dexterity })}  " +
                        "CON: ${formatStat(me.constitution, equipped.values.sumOf { it.item.constitution })}",
                ),
            )
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  INT: ${formatStat(me.intelligence, equipped.values.sumOf { it.item.intelligence })}  " +
                        "WIS: ${formatStat(me.wisdom, equipped.values.sumOf { it.item.wisdom })}  " +
                        "CHA: ${formatStat(me.charisma, equipped.values.sumOf { it.item.charisma })}",
                ),
            )
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Dmg : $dmgMin–$dmgMax          Armor: $armorDetail"))
            val group = groupSystem?.getGroup(sessionId)
            if (group != null) {
                val leaderName = players.get(group.leader)?.name ?: "?"
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  Group: ${group.members.size} members (leader: $leaderName)",
                    ),
                )
            }
        }
    }

    private suspend fun handleSpells(sessionId: SessionId) {
        if (abilitySystem == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Abilities are not available."))
            return
        }
        val known = abilitySystem.knownAbilities(sessionId)
        if (known.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You don't know any spells yet."))
        } else {
            players.withPlayer(sessionId) { me ->
                outbound.send(OutboundEvent.SendInfo(sessionId, "Known spells (Mana: ${me.mana}/${me.maxMana}):"))
                for (a in known) {
                    val remainingMs = abilitySystem.cooldownRemainingMs(sessionId, a.id)
                    val cdText =
                        if (remainingMs > 0) {
                            val remainingSec = ((remainingMs + 999) / 1000).coerceAtLeast(1)
                            "${remainingSec}s remaining"
                        } else if (a.cooldownMs > 0) {
                            "${a.cooldownMs / 1000}s cooldown"
                        } else {
                            "no cooldown"
                        }
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "  ${a.displayName}  — ${a.manaCost} mana, $cdText — ${a.description}",
                        ),
                    )
                }
            }
        }
        gmcpEmitter?.sendCharSkills(sessionId, known) { abilityId ->
            abilitySystem.cooldownRemainingMs(sessionId, abilityId)
        }
    }

    private suspend fun handleEffects(sessionId: SessionId) {
        if (statusEffects == null) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "No active effects."))
            return
        }
        val effects = statusEffects.activePlayerEffects(sessionId)
        if (effects.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "No active effects."))
        } else {
            outbound.send(OutboundEvent.SendInfo(sessionId, "Active effects:"))
            for (e in effects) {
                val remainingSec = ((e.remainingMs + 999) / 1000).coerceAtLeast(1)
                val stacksText = if (e.stacks > 1) " (x${e.stacks})" else ""
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  ${e.name}$stacksText [${e.type}] — ${remainingSec}s remaining",
                    ),
                )
            }
        }
    }

    private suspend fun handleBalance(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            outbound.send(OutboundEvent.SendInfo(sessionId, "You have ${me.gold} gold."))
        }
    }
}
