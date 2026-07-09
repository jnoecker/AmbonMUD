package dev.ambon.engine.commands.handlers

import dev.ambon.config.AkathavaeConfig
import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.Item
import dev.ambon.domain.items.ItemInstance
import dev.ambon.engine.AkathavaeSystem
import dev.ambon.engine.PlayerProgression
import dev.ambon.engine.PlayerState
import dev.ambon.engine.abilities.AbilitySystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import java.time.Clock

/**
 * The Akathavae pledge: the entry and exit points of the pacifist explorer path.
 *
 * At an Akathavae shrine (`akathavaeShrine: true` room flag), `pledge` freely binds
 * the player to the vow of the chroniclers of the Arcanum — combat is forbidden and
 * progression flows from illuminating the world instead. `renounce confirm` breaks
 * the vow at a shrine for a gold price, with a cooldown before re-pledging so the
 * two paths can't be flipped between to double-dip rewards.
 */
class AkathavaeHandler(
    ctx: EngineContext,
    private val config: AkathavaeConfig = AkathavaeConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val markVitalsDirty: ((SessionId) -> Unit)? = null,
    private val markStatsDirty: ((SessionId) -> Unit)? = null,
    private val akathavaeSystem: AkathavaeSystem? = null,
    private val progression: PlayerProgression? = null,
    private val abilitySystem: AbilitySystem? = null,
) : CommandHandler {
    private val players = ctx.players
    private val world = ctx.world
    private val outbound = ctx.outbound
    private val combat = ctx.combat
    private val items = ctx.items
    private val gmcpEmitter = ctx.gmcpEmitter
    private val classRegistry = ctx.classRegistry
    private val metrics = ctx.metrics

    override fun register(router: CommandRouter) {
        router.on<Command.Pledge> { sid, _ -> handlePledge(sid) }
        router.on<Command.Renounce> { sid, cmd -> handleRenounce(sid, cmd) }
        router.on<Command.Illuminate> { sid, cmd -> handleIlluminate(sid, cmd) }
        router.on<Command.Arcanum> { sid, cmd -> handleArcanum(sid, cmd) }
        router.on<Command.Wardrobe> { sid, cmd -> handleWardrobe(sid, cmd) }
    }

    /** Recorded items whose templates still exist and can be worn, as (templateId, template) pairs. */
    private fun wardrobeEntries(me: PlayerState): List<Pair<ItemId, Item>> =
        me.arcanum.items.keys
            .sorted()
            .mapNotNull { key -> items.getTemplate(ItemId(key))?.let { ItemId(key) to it } }
            .filter { (_, template) -> template.slot != null }

    private suspend fun handleWardrobe(sessionId: SessionId, cmd: Command.Wardrobe) {
        val me = players.get(sessionId) ?: return
        if (!me.isAkathavae) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "Only the pledged may draw from an Arcanum wardrobe. Seek a shrine.",
                ),
            )
            return
        }
        val entries = wardrobeEntries(me)
        if (cmd.keyword == null) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "[ Arcanum Wardrobe (${entries.size}) ]"))
            if (entries.isEmpty()) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "  No wearable items recorded yet — illuminate and discover more."))
                return
            }
            entries.chunked(3).forEach { row ->
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  " + row.joinToString(", ") { (_, t) -> "${t.displayName} (${t.slot!!.label()})" },
                    ),
                )
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "Use 'wardrobe <item>' to conjure and wear a recorded item."))
            return
        }

        val lower = cmd.keyword.lowercase()
        val match = entries.firstOrNull { (_, t) ->
            t.keyword.lowercase() == lower || t.displayName.lowercase().contains(lower)
        }
        if (match == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Your Arcanum holds no wearable page matching '${cmd.keyword}'."))
            return
        }
        val (templateId, template) = match
        val conjured = ItemInstance(id = templateId, item = template.copy(conjured = true))
        when (val result = items.equipConjured(sessionId, conjured)) {
            is ItemRegistry.EquipResult.Equipped -> {
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "${template.displayName} lifts from the pages of your Arcanum and settles onto your ${result.slot.label()}.",
                    ),
                )
            }
            is ItemRegistry.EquipResult.Swapped -> {
                val previousNote =
                    if (result.previousDissolved) {
                        "${result.previousItem.item.displayName} dissolves back into the pages"
                    } else {
                        "You stow ${result.previousItem.item.displayName}"
                    }
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "$previousNote as ${template.displayName} settles onto your ${result.slot.label()}.",
                    ),
                )
            }
            is ItemRegistry.EquipResult.NotWearable, ItemRegistry.EquipResult.NotFound -> {
                outbound.send(OutboundEvent.SendError(sessionId, "${template.displayName} cannot be worn."))
                return
            }
        }
        metrics.onGameEvent("akathavae", "wardrobe_wear")
        afterEquipChange(sessionId, combat, items, gmcpEmitter, markStatsDirty)
    }

    private suspend fun handleIlluminate(sessionId: SessionId, cmd: Command.Illuminate) {
        val system = akathavaeSystem
        if (system == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Illumination is not available."))
            return
        }
        system.illuminate(sessionId, cmd.target)
    }

    private suspend fun handleArcanum(sessionId: SessionId, cmd: Command.Arcanum) {
        val me = players.get(sessionId) ?: return
        val system = akathavaeSystem
        if (system == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "The Arcanum is not available."))
            return
        }
        if (!me.isAkathavae && me.arcanum.rooms.isEmpty() && me.arcanum.mobs.isEmpty() && me.arcanum.items.isEmpty()) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "Your Arcanum is unwritten. Take the Akathavae pledge at a shrine to begin recording the world.",
                ),
            )
            return
        }

        // Push the full journal to GMCP clients — the Arcanum panel renders from this.
        system.emitJournal(sessionId)

        val page = cmd.page ?: 1
        when (cmd.section) {
            null -> renderArcanumSummary(sessionId, me, system)
            "rooms", "places" -> renderArcanumSection(sessionId, "Places", "rooms", page, me.arcanum.rooms.keys) { key ->
                world.rooms[dev.ambon.domain.ids.RoomId(key)]?.title ?: key
            }
            "mobs", "creatures", "beasts" -> renderArcanumSection(sessionId, "Creatures", "mobs", page, me.arcanum.mobs.keys) { key ->
                val credit = system.worldFirstCredit("mob", key)
                val name = key.substringAfter(':').replace('_', ' ')
                if (credit?.first == me.name) "$name ★" else name
            }
            "items", "things" -> renderArcanumSection(sessionId, "Items", "items", page, me.arcanum.items.keys) { key ->
                key.substringAfter(':').replace('_', ' ')
            }
            else -> outbound.send(OutboundEvent.SendError(sessionId, "Usage: arcanum [rooms|mobs|items] [page]"))
        }
    }

    private suspend fun renderArcanumSummary(sessionId: SessionId, me: PlayerState, system: AkathavaeSystem) {
        outbound.send(OutboundEvent.SendInfo(sessionId, "[ The Arcanum of ${me.name} ]"))
        if (me.isAkathavae) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Pledged to the Akathavae — the world is your subject."))
        }
        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "  Recorded: ${me.arcanum.rooms.size} places, ${me.arcanum.mobs.size} creatures, ${me.arcanum.items.size} items.",
            ),
        )
        for (zone in system.recordedZones(me)) {
            val c = system.zoneCompletion(me, zone)
            if (c.roomsTotal == 0 && c.mobsTotal == 0 && c.itemsTotal == 0) continue
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  $zone: ${c.roomsRecorded}/${c.roomsTotal} places, ${c.mobsRecorded}/${c.mobsTotal} creatures, " +
                        "${c.itemsRecorded}/${c.itemsTotal} items.",
                ),
            )
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, "Use 'arcanum rooms|mobs|items [page]' to leaf through the pages."))
    }

    private suspend fun renderArcanumSection(
        sessionId: SessionId,
        title: String,
        sectionWord: String,
        page: Int,
        keys: Set<String>,
        displayName: (String) -> String,
    ) {
        if (keys.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "[ Arcanum — $title (0) ]"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  These pages are still blank."))
            return
        }
        val pageSize = MAX_SECTION_ROWS * SECTION_ENTRIES_PER_ROW
        val totalPages = (keys.size + pageSize - 1) / pageSize
        if (page < 1 || page > totalPages) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "The $title pages run 1 to $totalPages — try 'arcanum $sectionWord $totalPages'.",
                ),
            )
            return
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, "[ Arcanum — $title (${keys.size}) ]"))
        keys.sorted()
            .drop((page - 1) * pageSize)
            .take(pageSize)
            .chunked(SECTION_ENTRIES_PER_ROW)
            .forEach { row ->
                outbound.send(OutboundEvent.SendInfo(sessionId, "  " + row.joinToString(", ") { displayName(it) }))
            }
        if (totalPages > 1) {
            val hint = if (page < totalPages) " — 'arcanum $sectionWord ${page + 1}' for more" else ""
            outbound.send(OutboundEvent.SendInfo(sessionId, "  Page $page/$totalPages$hint."))
        }
    }

    private suspend fun requireShrine(sessionId: SessionId, me: PlayerState): Boolean {
        if (!config.enabled) {
            outbound.send(OutboundEvent.SendError(sessionId, "The Akathavae do not accept pledges in this world."))
            return false
        }
        return requireRoomFlag(
            sessionId,
            me,
            world,
            outbound,
            "Only at an Akathavae shrine can such a vow be spoken or unsaid.",
        ) { it.akathavaeShrine }
    }

    private suspend fun handlePledge(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        if (!requireShrine(sessionId, me)) return

        if (me.isAkathavae) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You have already given your voice to the Arcanum."))
            return
        }
        if (combat.isInCombat(sessionId)) {
            outbound.send(
                OutboundEvent.SendError(sessionId, "You cannot pledge peace with blood on your hands. Finish or flee this fight first."),
            )
            return
        }

        val now = clock.millis()
        val cooldownEndsAt = me.akathavaeRenouncedAtMs + config.repledgeCooldownMs
        if (me.akathavaeRenouncedAtMs > 0 && now < cooldownEndsAt) {
            val hoursLeft = ((cooldownEndsAt - now) + HOUR_MS - 1) / HOUR_MS
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "The Arcanum remembers your renunciation. The Akathavae will hear your pledge again in about $hoursLeft hour${if (hoursLeft == 1L) "" else "s"}.",
                ),
            )
            return
        }

        me.isAkathavae = true
        me.akathavaePledgedAtMs = now
        // The pledge claims the class itself: stash the old class for renounce,
        // become an Akathavae, and rescale vitals to the new class curve.
        val formerClass = me.playerClass
        me.preAkathavaeClass = formerClass
        me.playerClass = AKATHAVAE_CLASS
        me.unlockedClasses.add(AKATHAVAE_CLASS)
        // Setting aside the old class means setting aside its arts: drop every
        // learned ability so no former-class spell lingers under the vow. Clearing
        // the persisted set too keeps them gone across a relog.
        me.learnedAbilityIds.clear()
        abilitySystem?.clearLearnedAbilities(sessionId)
        applyClassChange(sessionId, me)
        metrics.onGameEvent("akathavae", "pledge")
        val formerName = classRegistry?.get(formerClass)?.displayName ?: formerClass
        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "You set aside the ways of the $formerName — you are an Akathavae now.",
            ),
        )
        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "You kneel before the shrine and speak the Pledge of the Akathavae. " +
                    "A hush settles over you: your weapons feel like strangers' tools, and the world " +
                    "sharpens into something worth recording. You are now a keeper of the Arcanum — " +
                    "go and illuminate what you find.",
            ),
        )
        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "While pledged you cannot fight. Visit new places, observe creatures, and discover items to earn experience.",
            ),
        )
        broadcastToRoomExcept(
            roomId = me.roomId,
            excludeSessionId = sessionId,
            message = "${me.name} kneels at the shrine and takes the Pledge of the Akathavae.",
            players = players,
            outbound = outbound,
        )
        // The shrine itself becomes the Arcanum's first page.
        akathavaeSystem?.onRoomVisited(sessionId)
        akathavaeSystem?.emitStatus(sessionId)
        markVitalsDirty?.invoke(sessionId)
    }

    private suspend fun handleRenounce(sessionId: SessionId, cmd: Command.Renounce) {
        val me = players.get(sessionId) ?: return
        if (!me.isAkathavae) {
            outbound.send(OutboundEvent.SendError(sessionId, "You are not bound by the Akathavae pledge."))
            return
        }
        if (!requireShrine(sessionId, me)) return

        if (!cmd.confirm) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "Renouncing the pledge costs ${config.renounceCostGold} gold and the Akathavae will not " +
                        "accept a new pledge from you for a time. Your Arcanum is kept — but it earns nothing while you bear arms.",
                ),
            )
            outbound.send(OutboundEvent.SendInfo(sessionId, "Type 'renounce confirm' if you are certain."))
            return
        }
        if (me.gold < config.renounceCostGold) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "Breaking a sacred vow demands an offering of ${config.renounceCostGold} gold — you have only ${me.gold}.",
                ),
            )
            return
        }

        me.gold -= config.renounceCostGold
        me.isAkathavae = false
        me.akathavaeRenouncedAtMs = clock.millis()
        // Restore the class held before the pledge. Legacy pledges (taken before
        // the class switch existed) have no stash — fall back to the first
        // non-Akathavae unlocked class, or leave the class untouched.
        val restored = me.preAkathavaeClass
            ?: me.unlockedClasses.firstOrNull { !it.equals(AKATHAVAE_CLASS, ignoreCase = true) }
        me.preAkathavaeClass = null
        me.unlockedClasses.removeIf { it.equals(AKATHAVAE_CLASS, ignoreCase = true) }
        if (restored != null && me.playerClass.equals(AKATHAVAE_CLASS, ignoreCase = true)) {
            me.playerClass = restored
            applyClassChange(sessionId, me)
            val restoredName = classRegistry?.get(restored)?.displayName ?: restored
            outbound.send(OutboundEvent.SendInfo(sessionId, "You take up the ways of the $restoredName once more."))
        }
        metrics.onGameEvent("akathavae", "renounce")
        val dissolved = items.dissolveConjuredEquipment(sessionId)
        if (dissolved.isNotEmpty()) {
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "${dissolved.joinToString(", ") {
                        it.item.displayName
                    }} dissolve${if (dissolved.size == 1) "s" else ""} back into the pages of your Arcanum.",
                ),
            )
            afterEquipChange(sessionId, combat, items, gmcpEmitter, markStatsDirty)
        }
        outbound.send(
            OutboundEvent.SendInfo(
                sessionId,
                "You lay ${config.renounceCostGold} gold upon the shrine and unsay your vow. " +
                    "The hush lifts. Your hands remember the weight of weapons.",
            ),
        )
        broadcastToRoomExcept(
            roomId = me.roomId,
            excludeSessionId = sessionId,
            message = "${me.name} lays an offering on the shrine and renounces the Pledge of the Akathavae.",
            players = players,
            outbound = outbound,
        )
        akathavaeSystem?.emitStatus(sessionId)
        markVitalsDirty?.invoke(sessionId)
    }

    /**
     * Applies the side effects of an active-class change: vitals rescale to the
     * new class curve (preserving prestige/effect bonuses), known abilities
     * recompute against the updated class set, and the client resyncs.
     */
    private suspend fun applyClassChange(sessionId: SessionId, me: PlayerState) {
        progression?.recomputeVitalCaps(me)
        // While pledged, recompute abilities against the Akathavae class alone so
        // no former-class spells resolve from a still-unlocked old class. On
        // renounce (no longer pledged) the full unlocked set restores them.
        val abilityClasses = if (me.isAkathavae) setOf(AKATHAVAE_CLASS) else me.unlockedClasses
        abilitySystem?.recomputeKnownAbilities(sessionId, me.level, abilityClasses)
        gmcpEmitter?.sendCharName(sessionId, me)
        gmcpEmitter?.sendCharClasses(sessionId, me.unlockedClasses.ifEmpty { setOf(me.playerClass) }, me.playerClass)
        abilitySystem?.let { abilities ->
            gmcpEmitter?.sendCharSkills(
                sessionId,
                abilities.knownAbilities(sessionId),
                cooldownRemainingMs = { abilityId -> abilities.cooldownRemainingMs(sessionId, abilityId) },
                player = me,
                manaCostFor = { ability -> abilities.computeManaCost(me, ability) },
            )
        }
        markVitalsDirty?.invoke(sessionId)
        markStatsDirty?.invoke(sessionId)
    }

    companion object {
        /** Class id entered by pledging; must match the AKATHAVAE definition in `engine.classes`. */
        const val AKATHAVAE_CLASS = "AKATHAVAE"
        private const val HOUR_MS = 3_600_000L
        private const val MAX_SECTION_ROWS = 25
        private const val SECTION_ENTRIES_PER_ROW = 4
    }
}
