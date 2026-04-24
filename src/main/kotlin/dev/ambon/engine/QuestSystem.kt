package dev.ambon.engine

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.ids.idZone
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.quest.ObjectiveProgress
import dev.ambon.domain.quest.QuestDef
import dev.ambon.domain.quest.QuestObjectiveDef
import dev.ambon.domain.quest.QuestRewards
import dev.ambon.domain.quest.QuestState
import dev.ambon.domain.world.ScalingMode
import dev.ambon.domain.world.World
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.quest.CompletionHandlerRegistry
import dev.ambon.engine.quest.ObjectiveHandlerRegistry
import java.time.Clock

class QuestSystem(
    private val registry: QuestRegistry,
    private val players: PlayerRegistry,
    private val items: ItemRegistry,
    private val outbound: OutboundBus,
    private val clock: Clock = Clock.systemUTC(),
    private val objectiveHandlers: ObjectiveHandlerRegistry = ObjectiveHandlerRegistry.withDefaults(),
    private val completionHandlers: CompletionHandlerRegistry = CompletionHandlerRegistry.withDefaults(),
    private val reputationSystem: ReputationSystem? = null,
    private val progression: PlayerProgression? = null,
    private val world: World? = null,
) {
    /** Invoked after a quest is successfully completed; used by AchievementSystem. */
    var onQuestCompleted: (suspend (SessionId, String) -> Unit)? = null

    /** Invoked to emit quest GMCP; set by GameEngine during wiring. */
    var onQuestListChanged: (suspend (SessionId) -> Unit)? = null
    var onQuestObjectiveUpdated: (suspend (SessionId, String, Int, Int, Int) -> Unit)? = null
    var onQuestCompletedGmcp: (suspend (SessionId, String, String) -> Unit)? = null

    /** Invoked when a quest reward grants enough XP to level the player up. */
    var onLevelUp: (suspend (SessionId, LevelUpResult) -> Unit)? = null

    /**
     * Returns quests offered by this mob that the player can accept.
     *
     * Excludes quests already active, already completed, whose reputation ceiling
     * has been exceeded (quest "disappears"), or whose reputation floor is unmet
     * (surfaced via [hintedQuests] instead so the giver can acknowledge them).
     */
    fun availableQuests(
        sessionId: SessionId,
        mobId: String,
    ): List<QuestDef> {
        val ps = players.get(sessionId) ?: return emptyList()
        return registry.questsForMob(mobId).filter { quest ->
            isAcceptable(ps, quest)
        }
    }

    /**
     * Quests the giver knows about but the player can't yet accept because a
     * reputation `min` is unmet. Used to hint at gated content rather than
     * leaving the player wondering why it isn't appearing.
     */
    fun hintedQuests(
        sessionId: SessionId,
        mobId: String,
    ): List<QuestDef> {
        val ps = players.get(sessionId) ?: return emptyList()
        return registry.questsForMob(mobId).filter { quest ->
            if (ps.activeQuests.containsKey(quest.id) || ps.completedQuestIds.contains(quest.id)) return@filter false
            if (exceedsRepMax(ps, quest)) return@filter false
            belowRepMin(ps, quest)
        }
    }

    private fun isAcceptable(ps: PlayerState, quest: QuestDef): Boolean {
        if (ps.activeQuests.containsKey(quest.id) || ps.completedQuestIds.contains(quest.id)) return false
        if (exceedsRepMax(ps, quest)) return false
        if (belowRepMin(ps, quest)) return false
        return true
    }

    private fun belowRepMin(ps: PlayerState, quest: QuestDef): Boolean {
        val req = quest.requiredReputation ?: return false
        val min = req.min ?: return false
        val rep = reputationSystem ?: return false
        return rep.getStanding(ps, req.faction) < min
    }

    private fun exceedsRepMax(ps: PlayerState, quest: QuestDef): Boolean {
        val req = quest.requiredReputation ?: return false
        val max = req.max ?: return false
        val rep = reputationSystem ?: return false
        return rep.getStanding(ps, req.faction) > max
    }

    /** Returns mob IDs that offer quests the player can accept. */
    fun questAvailableMobIds(sessionId: SessionId, mobIds: Collection<String>): Set<String> {
        val ps = players.get(sessionId) ?: return emptySet()
        return mobIds.filterTo(mutableSetOf()) { mobId ->
            registry.questsForMob(mobId).any { quest -> isAcceptable(ps, quest) }
        }
    }

    /** Returns mob IDs that have a turn-in quest whose objectives the player has completed. */
    fun questCompleteMobIds(sessionId: SessionId, mobIds: Collection<String>): Set<String> {
        val ps = players.get(sessionId) ?: return emptySet()
        return mobIds.filterTo(mutableSetOf()) { mobId ->
            registry.questsForMob(mobId).any { quest ->
                val handler = completionHandlers.get(quest.completionType)
                handler?.requiresNpcTurnIn == true &&
                    ps.activeQuests[quest.id]?.objectives?.all { it.isComplete } == true
            }
        }
    }

    /**
     * Accept a quest by id. Returns an error string, or null on success.
     */
    suspend fun acceptQuest(
        sessionId: SessionId,
        questId: String,
    ): String? {
        val ps = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        val quest = registry.get(questId) ?: return "Unknown quest '$questId'."
        if (ps.activeQuests.containsKey(questId)) return "You are already on that quest."
        if (ps.completedQuestIds.contains(questId)) return "You have already completed that quest."
        if (exceedsRepMax(ps, quest)) {
            return "You are too well-regarded for this quest."
        }
        if (belowRepMin(ps, quest)) {
            val req = quest.requiredReputation!!
            val factionName = reputationSystem?.getFaction(req.faction)?.name ?: req.faction
            return "You lack the standing with $factionName to take this quest."
        }

        val seededObjectives =
            quest.objectives.map { objDef ->
                val initial = ObjectiveProgress(current = 0, required = objDef.count)
                seedInitialProgress(sessionId, objDef, initial) ?: initial
            }
        val state =
            QuestState(
                questId = questId,
                acceptedAtEpochMs = clock.millis(),
                objectives = seededObjectives,
            )
        ps.activeQuests = ps.activeQuests + (questId to state)
        players.persistPlayer(ps.sessionId)

        outbound.send(OutboundEvent.SendInfo(sessionId, "Quest accepted: ${quest.name}"))
        for ((index, obj) in quest.objectives.withIndex()) {
            outbound.send(OutboundEvent.SendText(sessionId, "  - ${obj.description}"))
            val prog = seededObjectives[index]
            if (prog.current > 0) {
                sendObjectiveProgress(sessionId, obj.description, prog)
                onQuestObjectiveUpdated?.invoke(sessionId, questId, index, prog.current, prog.required)
            }
        }
        onQuestListChanged?.invoke(sessionId)
        // An already-satisfied collect objective (e.g. player already holds the items)
        // should auto-complete the quest when its completion type allows it.
        checkAutoComplete(sessionId, ps.activeQuests)
        return null
    }

    /**
     * Seeds initial progress for an objective based on current player state at the moment
     * of quest acceptance. This covers the case where the player already holds items
     * required by a COLLECT objective — without this, the objective would sit at 0/N
     * until the next pickup, at which point counting existing inventory would appear
     * to "jump" progress.
     *
     * Returns the seeded [ObjectiveProgress] or null when no seed applies (no-op).
     */
    private fun seedInitialProgress(
        sessionId: SessionId,
        objDef: QuestObjectiveDef,
        initial: ObjectiveProgress,
    ): ObjectiveProgress? {
        val handler = objectiveHandlers.collectHandler(objDef.type) ?: return null
        val targetIdRaw = objDef.targetId
        // Count any inventory items whose id matches the objective target, using the
        // same loose-suffix semantics the collect handler applies at pickup time.
        val currentCount =
            items.inventory(sessionId).count { inv ->
                val itemId = inv.id.value
                itemId == targetIdRaw || itemId.endsWith(":${targetIdRaw.substringAfterLast(':')}")
            }
        if (currentCount <= 0) return null
        return handler.advance(objDef, initial, targetIdRaw, currentCount)
    }

    /**
     * Abandon a quest by name hint. Returns an error string, or null on success.
     */
    suspend fun abandonQuest(
        sessionId: SessionId,
        nameHint: String,
    ): String? {
        val ps = players.get(sessionId) ?: return ERR_NOT_CONNECTED
        val questId = findActiveQuestId(ps.activeQuests, nameHint) ?: return "No active quest matching '$nameHint'."
        val quest = registry.get(questId)
        ps.activeQuests = ps.activeQuests - questId
        players.persistPlayer(ps.sessionId)
        outbound.send(OutboundEvent.SendInfo(sessionId, "Quest abandoned: ${quest?.name ?: questId}"))
        onQuestListChanged?.invoke(sessionId)
        return null
    }

    /**
     * Called when a player kills a mob. Updates KILL objectives and checks completion.
     */
    suspend fun onMobKilled(
        sessionId: SessionId,
        templateKey: String,
    ) {
        advanceObjectives(sessionId) { objDef, prog ->
            val handler = objectiveHandlers.killHandler(objDef.type) ?: return@advanceObjectives null
            handler.advance(objDef, prog, templateKey)
        }
    }

    /**
     * Called when a player picks up an item. Updates COLLECT objectives and checks completion.
     */
    suspend fun onItemCollected(
        sessionId: SessionId,
        item: ItemInstance,
    ) {
        advanceObjectives(sessionId) { objDef, prog ->
            val handler = objectiveHandlers.collectHandler(objDef.type) ?: return@advanceObjectives null
            val itemId = item.id.value
            val currentCount = items.inventory(sessionId).count { inv -> inv.id.value == itemId }
            handler.advance(objDef, prog, itemId, currentCount)
        }
    }

    /**
     * Iterates all active quest objectives for [sessionId], calling [advance] for each
     * non-complete objective. When [advance] returns a non-null [ObjectiveProgress], the
     * objective is updated and the player is persisted with an auto-complete check.
     */
    private suspend fun advanceObjectives(
        sessionId: SessionId,
        advance: (QuestObjectiveDef, ObjectiveProgress) -> ObjectiveProgress?,
    ) {
        val ps = players.get(sessionId) ?: return
        val updatedQuests = ps.activeQuests.toMutableMap()
        var changed = false

        for ((questId, state) in ps.activeQuests) {
            val quest = registry.get(questId) ?: continue
            val newObjectives = state.objectives.toMutableList()
            var questChanged = false

            for ((index, objDef) in quest.objectives.withIndex()) {
                val prog = newObjectives[index]
                if (prog.isComplete) continue
                val updated = advance(objDef, prog) ?: continue
                newObjectives[index] = updated
                questChanged = true
                sendObjectiveProgress(sessionId, objDef.description, updated)
                onQuestObjectiveUpdated?.invoke(sessionId, questId, index, updated.current, updated.required)
            }

            if (questChanged) {
                updatedQuests[questId] = state.copy(objectives = newObjectives)
                changed = true
            }
        }

        if (changed) {
            ps.activeQuests = updatedQuests
            players.persistPlayer(ps.sessionId)
            checkAutoComplete(sessionId, ps.activeQuests)
        }
    }

    /**
     * Format the quest log for a player.
     */
    fun formatQuestLog(sessionId: SessionId): String {
        val ps = players.get(sessionId) ?: return "No quests."
        if (ps.activeQuests.isEmpty()) return "You have no active quests."
        return buildString {
            appendLine("Active Quests:")
            for ((questId, state) in ps.activeQuests) {
                val quest = registry.get(questId)
                val name = quest?.name ?: questId
                appendLine("  $name")
                for ((index, objDef) in (quest?.objectives ?: emptyList()).withIndex()) {
                    val prog = state.objectives.getOrNull(index) ?: continue
                    val status = if (prog.isComplete) "DONE" else "${prog.current}/${prog.required}"
                    appendLine("    - ${objDef.description}: $status")
                }
            }
        }.trimEnd()
    }

    /**
     * Format detailed quest info for one quest matching nameHint.
     */
    fun formatQuestInfo(
        sessionId: SessionId,
        nameHint: String,
    ): String {
        val ps = players.get(sessionId) ?: return "No quests."
        val questId =
            findActiveQuestId(ps.activeQuests, nameHint)
                ?: return "No active quest matching '$nameHint'."
        val quest = registry.get(questId) ?: return "Quest data not found."
        val state = ps.activeQuests[questId] ?: return "Quest not active."
        return buildString {
            appendLine("Quest: ${quest.name}")
            appendLine(quest.description)
            appendLine("Objectives:")
            for ((index, objDef) in quest.objectives.withIndex()) {
                val prog = state.objectives.getOrNull(index)
                val status = if (prog?.isComplete == true) "DONE" else "${prog?.current ?: 0}/${prog?.required ?: objDef.count}"
                appendLine("  - ${objDef.description}: $status")
            }
            appendLine("Rewards:")
            if (quest.rewards.xp > 0) appendLine("  XP: ${quest.rewards.xp}")
            if (quest.rewards.gold > 0) append("  Gold: ${quest.rewards.gold}")
        }.trimEnd()
    }

    private suspend fun checkAutoComplete(
        sessionId: SessionId,
        activeQuests: Map<String, QuestState>,
    ) {
        for ((questId, state) in activeQuests) {
            val quest = registry.get(questId) ?: continue
            val handler = completionHandlers.get(quest.completionType) ?: continue
            if (!handler.autoCompletes) continue
            if (state.objectives.all { it.isComplete }) {
                completeQuest(sessionId, questId, quest.rewards)
            }
        }
    }

    private suspend fun completeQuest(
        sessionId: SessionId,
        questId: String,
        rewards: QuestRewards,
    ) {
        val ps = players.get(sessionId) ?: return
        val quest = registry.get(questId) ?: return

        ps.activeQuests = ps.activeQuests - questId
        ps.completedQuestIds = ps.completedQuestIds + questId

        outbound.send(OutboundEvent.SendInfo(sessionId, "Quest complete: ${quest.name}!"))
        onQuestCompletedGmcp?.invoke(sessionId, questId, quest.name)
        onQuestCompleted?.invoke(sessionId, questId)

        // Zone scaling (phase 4): if the quest's zone declares non-STATIC scaling,
        // the "effective level" for XP computation and diminishing tracks the
        // player's current level instead of the authored quest level.
        val zoneScaling = world?.zoneScaling(idZone(quest.id))
        val effectiveLevel = when {
            zoneScaling == null -> quest.level ?: ps.level
            zoneScaling.mode == ScalingMode.STATIC -> quest.level ?: ps.level
            else -> zoneScaling.resolveLevel(ps.level, quest.level)
        }

        val effectiveRewards =
            if (rewards.xp == 0L && quest.difficulty != null && progression != null) {
                val computed = progression.computeQuestXp(quest.difficulty, effectiveLevel)
                if (computed > 0L) rewards.copy(xp = computed) else rewards
            } else {
                rewards
            }

        // Diminishing applies in STATIC zones only. In BOUNDED / PLAYER zones
        // the content is already scaled to the player, so charging an additional
        // over-level penalty contradicts the zone's explicit opt-in to scaling.
        val sourceLevelForDiminishing =
            if (zoneScaling != null && zoneScaling.mode != ScalingMode.STATIC) null else quest.level
        grantRewards(
            sessionId,
            effectiveRewards,
            ps,
            players,
            outbound,
            progression,
            sourceLevelForDiminishing,
            onLevelUp = { result -> onLevelUp?.invoke(sessionId, result) },
        )
        if (effectiveRewards.xp == 0L) players.persistPlayer(ps.sessionId)
    }

    private suspend fun sendObjectiveProgress(
        sessionId: SessionId,
        description: String,
        updated: ObjectiveProgress,
    ) {
        if (updated.isComplete) {
            outbound.send(OutboundEvent.SendText(sessionId, "[Quest] $description: complete!"))
        } else {
            outbound.send(OutboundEvent.SendText(sessionId, "[Quest] $description: ${updated.current}/${updated.required}"))
        }
    }

    private fun findActiveQuestId(
        activeQuests: Map<String, QuestState>,
        nameHint: String,
    ): String? {
        val lower = nameHint.trim().lowercase()
        for ((questId, _) in activeQuests) {
            val quest = registry.get(questId) ?: continue
            if (quest.name.lowercase().contains(lower) || questId.lowercase().contains(lower)) {
                return questId
            }
        }
        return null
    }
}
