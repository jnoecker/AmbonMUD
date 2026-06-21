package dev.ambon.engine.commands.handlers

import dev.ambon.domain.StatDefinition
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.AkathavaeSystem
import dev.ambon.engine.CurrencySystem
import dev.ambon.engine.GroupSystem
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.ceilSeconds
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.commands.onStaff
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.status.StatusEffectSystem

class ProgressionHandler(
    ctx: EngineContext,
    private val progression: PlayerProgression = PlayerProgression(),
    private val abilitySystem: AbilitySystem? = null,
    private val statusEffects: StatusEffectSystem? = null,
    private val groupSystem: GroupSystem? = null,
    private val currencySystem: CurrencySystem? = null,
    private val akathavaeSystem: AkathavaeSystem? = null,
) : CommandHandler {
    private val players = ctx.players
    private val items = ctx.items
    private val combat = ctx.combat
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter
    private val classRegistry = ctx.classRegistry
    private val raceRegistry = ctx.raceRegistry
    private val statRegistry = ctx.statRegistry
    private val genderRegistry = ctx.genderRegistry

    override fun register(router: CommandRouter) {
        router.on<Command.Score> { sid, _ -> handleScore(sid) }
        router.on<Command.Spells> { sid, _ -> handleSpells(sid) }
        router.on<Command.Effects> { sid, _ -> handleEffects(sid) }
        router.on<Command.Balance> { sid, _ -> handleBalance(sid) }
        router.on<Command.SetGender> { sid, cmd -> handleSetGender(sid, cmd) }
        router.on<Command.Describe> { sid, cmd -> handleDescribe(sid, cmd) }
        router.on<Command.DescribeClear> { sid, _ -> handleDescribeClear(sid) }
        router.onStaff<Command.DescribeCheck> { sid, cmd -> handleDescribeCheck(sid, cmd) }
    }

    private suspend fun handleScore(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val equipped = items.equipment(sessionId)

            val attackBonus = equipped.values.sumOf { it.item.damage }
            val dmgRange = combat.damageRangeForDisplay(me, me.stats, attackBonus)
            val dmgMin = dmgRange.first
            val dmgMax = dmgRange.last

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
                        equipped.entries
                            .filter { (_, inst) -> inst.item.armor > 0 }
                            .sortedBy { (slot, _) -> slot.name }
                            .joinToString(", ") { (slot, inst) -> "${slot.label()}: ${inst.item.displayName}" }
                    "+$armorTotal ($parts)"
                } else {
                    "+0"
                }

            val raceName = raceRegistry?.get(me.race)?.displayName ?: me.race
            val className = classRegistry?.get(me.playerClass)?.displayName ?: me.playerClass

            outbound.send(OutboundEvent.SendInfo(sessionId, "[ ${me.name} — Level ${me.level} $raceName $className ]"))
            if (me.isAkathavae) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "  Akathavae — pledged to the Arcanum; combat forsworn"))
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "  HP  : ${me.hp} / ${me.maxHp}      XP : $xpLine"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Mana: ${me.mana} / ${me.maxMana}"))
            val statDefs: List<StatDefinition> = statRegistry?.all() ?: listOf(
                StatDefinition("STR", "Strength", "STR"),
                StatDefinition("DEX", "Dexterity", "DEX"),
                StatDefinition("CON", "Constitution", "CON"),
                StatDefinition("INT", "Intelligence", "INT"),
                StatDefinition("WIS", "Wisdom", "WIS"),
                StatDefinition("CHA", "Charisma", "CHA"),
            )
            val equipStats = items.equipmentBonuses(sessionId, classRegistry?.get(me.playerClass)).stats
            statDefs.chunked(3).forEach { row ->
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  " + row.joinToString("  ") { def ->
                            val base = me.stats[def.id]
                            val equipBonus = equipStats[def.id]
                            "${def.abbreviation}: ${formatStat(base, equipBonus)}"
                        },
                    ),
                )
            }
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
            if (me.prestigeLevel > 0) {
                outbound.send(
                    OutboundEvent.SendInfo(sessionId, "  Prestige: Rank ${me.prestigeLevel}"),
                )
            }
            val currDefs = currencySystem?.definitions() ?: emptyMap()
            if (currDefs.isNotEmpty()) {
                val summary = currDefs.entries.joinToString(", ") { (id, def) ->
                    val bal = currencySystem!!.balance(me, id)
                    "${def.abbreviation}: $bal"
                }
                outbound.send(OutboundEvent.SendInfo(sessionId, "  Currencies: $summary"))
            }
        }
    }

    private suspend fun handleSpells(sessionId: SessionId) {
        // For the Akathavae the Arcanum *is* the spellbook: they wield no spells,
        // so 'spells'/'abilities'/'skills' leafs through their journal instead.
        val me = players.get(sessionId)
        if (me != null && me.isAkathavae) {
            akathavaeSystem?.emitJournal(sessionId)
            outbound.send(OutboundEvent.SendInfo(sessionId, "As an Akathavae you wield no spells — your art is the Arcanum."))
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  Recorded: ${me.arcanum.rooms.size} places, ${me.arcanum.mobs.size} creatures, ${me.arcanum.items.size} items.",
                ),
            )
            outbound.send(OutboundEvent.SendInfo(sessionId, "Type 'arcanum' to leaf through its pages."))
            return
        }
        val abilities = requireSystemOrNull(sessionId, abilitySystem, "Abilities", outbound) ?: return
        val known = abilities.knownAbilities(sessionId)
        if (known.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You don't know any spells yet."))
        } else {
            players.withPlayer(sessionId) { me ->
                outbound.send(OutboundEvent.SendInfo(sessionId, "Known spells (Mana: ${me.mana}/${me.maxMana}):"))
                for (a in known) {
                    val remainingMs = abilities.cooldownRemainingMs(sessionId, a.id)
                    val cdText =
                        if (remainingMs > 0) {
                            "${remainingMs.ceilSeconds()}s remaining"
                        } else if (a.cooldownMs > 0) {
                            "${a.cooldownMs / 1000}s cooldown"
                        } else {
                            "no cooldown"
                        }
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "  ${a.displayName}  — ${abilities.computeManaCost(me, a)} mana, $cdText — ${a.description}",
                        ),
                    )
                }
            }
        }
        players.withPlayer(sessionId) { me ->
            gmcpEmitter?.sendCharSkills(
                sessionId,
                known,
                cooldownRemainingMs = { abilityId -> abilities.cooldownRemainingMs(sessionId, abilityId) },
                player = me,
                manaCostFor = { ability -> abilities.computeManaCost(me, ability) },
            )
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
                val remainingSec = e.remainingMs.ceilSeconds()
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

    private suspend fun handleSetGender(sessionId: SessionId, cmd: Command.SetGender) {
        val genderId = cmd.gender.lowercase()
        val gender = genderRegistry?.get(genderId)
        if (gender == null) {
            val options = genderRegistry?.allIds()?.joinToString(", ") ?: "unknown"
            outbound.send(OutboundEvent.SendError(sessionId, "Unknown gender '${cmd.gender}'. Options: $options"))
            return
        }
        players.withPlayer(sessionId) { me ->
            me.gender = gender.id
            outbound.send(OutboundEvent.SendInfo(sessionId, "Gender set to ${gender.displayName}."))
            gmcpEmitter?.sendCharName(sessionId, me)
        }
    }

    private suspend fun handleDescribe(sessionId: SessionId, cmd: Command.Describe) {
        if (cmd.text.length > MAX_DESCRIPTION_LENGTH) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "Description too long (max $MAX_DESCRIPTION_LENGTH characters).",
                ),
            )
            return
        }
        players.withPlayer(sessionId) { me ->
            me.description = cmd.text
            outbound.send(OutboundEvent.SendInfo(sessionId, "Description set."))
        }
    }

    private suspend fun handleDescribeClear(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            me.description = ""
            outbound.send(OutboundEvent.SendInfo(sessionId, "Description cleared."))
        }
    }

    private suspend fun handleDescribeCheck(sessionId: SessionId, cmd: Command.DescribeCheck) {
        val target = players.getByName(cmd.targetName)
        if (target == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Player '${cmd.targetName}' is not online."))
            return
        }
        val desc = target.description.ifEmpty { "(no description set)" }
        outbound.send(OutboundEvent.SendInfo(sessionId, "${target.name}'s description: $desc"))
    }

    companion object {
        /** Maximum length for a player-written character description. */
        const val MAX_DESCRIPTION_LENGTH = 500
    }
}
