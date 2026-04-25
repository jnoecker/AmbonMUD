package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.quest.QuestDef
import dev.ambon.engine.AchievementRegistry
import dev.ambon.engine.AchievementSystem
import dev.ambon.engine.HouseEntryResult
import dev.ambon.engine.HousingSystem
import dev.ambon.engine.QuestRegistry
import dev.ambon.engine.QuestSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueOutcome
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent

class DialogueQuestHandler(
    private val ctx: EngineContext,
    private val dialogueSystem: DialogueSystem? = null,
    private val questSystem: QuestSystem? = null,
    private val questRegistry: QuestRegistry = QuestRegistry(),
    private val achievementSystem: AchievementSystem? = null,
    private val achievementRegistry: AchievementRegistry = AchievementRegistry(),
    private val housingSystem: HousingSystem? = null,
) : CommandHandler {
    private val players = ctx.players
    private val mobs = ctx.mobs
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Talk> { sid, cmd -> handleTalk(sid, cmd) }
        router.on<Command.DialogueChoice> { sid, cmd -> handleDialogueChoice(sid, cmd) }
        router.on<Command.DialogueEnd> { sid, _ -> handleDialogueEnd(sid) }
        router.on<Command.QuestLog> { sid, _ -> handleQuestLog(sid) }
        router.on<Command.QuestInfo> { sid, cmd -> handleQuestInfo(sid, cmd) }
        router.on<Command.QuestAbandon> { sid, cmd -> handleQuestAbandon(sid, cmd) }
        router.on<Command.QuestAccept> { sid, cmd -> handleQuestAccept(sid, cmd) }
        router.on<Command.QuestAcceptById> { sid, cmd -> handleQuestAcceptById(sid, cmd) }
        router.on<Command.QuestTurnIn> { sid, cmd -> handleQuestTurnIn(sid, cmd) }
        router.on<Command.QuestTurnInById> { sid, cmd -> handleQuestTurnInById(sid, cmd) }
        router.on<Command.QuestOffers> { sid, cmd -> handleQuestOffers(sid, cmd) }
        router.on<Command.AchievementList> { sid, _ -> handleAchievementList(sid) }
        router.on<Command.TitleSet> { sid, cmd -> handleTitleSet(sid, cmd) }
        router.on<Command.TitleClear> { sid, _ -> handleTitleClear(sid) }
    }

    private suspend fun handleTalk(
        sessionId: SessionId,
        cmd: Command.Talk,
    ) {
        if (dialogueSystem == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Nobody here wants to talk."))
            return
        }
        outbound.sendIfError(sessionId, dialogueSystem.startConversation(sessionId, cmd.target))
    }

    private suspend fun handleQuestOffers(
        sessionId: SessionId,
        cmd: Command.QuestOffers,
    ) {
        val qs = requireSystemOrNull(sessionId, questSystem, "Quests", outbound) ?: return
        val me = players.get(sessionId) ?: return
        val mob = mobs.findInRoomByKeyword(me.roomId, cmd.mobKeyword.trim()).firstOrNull()
        if (mob == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "You don't see anyone like that here."))
            return
        }
        val offers = qs.questOffersFor(sessionId, mob.id.value)
        if (offers.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "${mob.name} has no quests for you."))
            // Still emit so the client can dismiss any open panel.
            gmcpEmitter?.sendQuestAvailable(sessionId, emptyList())
            return
        }
        for (entry in offers) {
            val tag = when {
                entry.readyToTurnIn -> "ready to turn in"
                entry.lockedReason != null -> entry.lockedReason
                else -> entry.description
            }
            outbound.send(OutboundEvent.SendText(sessionId, "[Quest] ${entry.name} — $tag"))
        }
        gmcpEmitter?.sendQuestAvailable(sessionId, offers)
    }

    private suspend fun handleDialogueChoice(
        sessionId: SessionId,
        cmd: Command.DialogueChoice,
    ) {
        if (dialogueSystem?.isInConversation(sessionId) != true) {
            outbound.send(OutboundEvent.SendError(sessionId, "Huh?"))
            return
        }
        when (val outcome = dialogueSystem.selectChoice(sessionId, cmd.optionNumber)) {
            is DialogueOutcome.Err -> outbound.send(OutboundEvent.SendError(sessionId, outcome.message))
            is DialogueOutcome.Ok -> outcome.action?.let { handleDialogueAction(sessionId, it) }
        }
    }

    private suspend fun handleDialogueEnd(sessionId: SessionId) {
        dialogueSystem?.endConversation(sessionId)
    }

    private suspend fun handleDialogueAction(
        sessionId: SessionId,
        action: String,
    ) {
        if (action.startsWith("unlock_flag:")) {
            handleUnlockFlag(sessionId, action.removePrefix("unlock_flag:").trim())
            return
        }
        when (action) {
            "set_recall" -> {
                val me = players.get(sessionId) ?: return
                players.setRecallRoom(sessionId, me.roomId)
                outbound.send(
                    OutboundEvent.SendText(
                        sessionId,
                        "The innkeeper marks your name in the ledger. This inn is now your recall point.",
                    ),
                )
            }
            "enter_house" -> {
                val hs = housingSystem
                val me = players.get(sessionId) ?: return
                if (hs == null || !me.hasHouse) {
                    outbound.send(OutboundEvent.SendError(sessionId, "You don't own a house."))
                    return
                }
                val origin = me.roomId
                when (val result = hs.enterOwnHouse(sessionId, origin)) {
                    is HouseEntryResult.Success -> {
                        movePlayerWithNotify(
                            sessionId,
                            origin,
                            result.entryRoomId,
                            "steps through a doorway and vanishes.",
                            "appears through a shimmering doorway.",
                            players,
                            outbound,
                            gmcpEmitter,
                        )
                        outbound.send(OutboundEvent.SendText(sessionId, "You step through the doorway into your house."))
                        ctx.sendLook(sessionId)
                    }
                    is HouseEntryResult.Error -> {
                        outbound.send(OutboundEvent.SendError(sessionId, result.message))
                    }
                }
            }
        }
    }

    private suspend fun handleUnlockFlag(sessionId: SessionId, flag: String) {
        if (flag.isEmpty()) return
        val me = players.get(sessionId) ?: return
        if (me.dialogueFlags.add(flag)) {
            players.persistPlayer(sessionId)
            // A gated quest may now be acceptable — reuse the existing quest-list
            // change hook so the canvas refreshes its quest indicators on this NPC.
            questSystem?.onQuestListChanged?.invoke(sessionId)
        }
    }

    private suspend fun handleQuestLog(sessionId: SessionId) {
        val qs = requireSystemOrNull(sessionId, questSystem, "Quests", outbound) ?: return
        outbound.send(OutboundEvent.SendInfo(sessionId, qs.formatQuestLog(sessionId)))
    }

    private suspend fun handleQuestInfo(
        sessionId: SessionId,
        cmd: Command.QuestInfo,
    ) {
        val qs = requireSystemOrNull(sessionId, questSystem, "Quests", outbound) ?: return
        outbound.send(OutboundEvent.SendInfo(sessionId, qs.formatQuestInfo(sessionId, cmd.nameHint)))
    }

    private suspend fun handleQuestAbandon(
        sessionId: SessionId,
        cmd: Command.QuestAbandon,
    ) {
        val qs = requireSystemOrNull(sessionId, questSystem, "Quests", outbound) ?: return
        outbound.sendIfError(sessionId, qs.abandonQuest(sessionId, cmd.nameHint))
    }

    private suspend fun handleQuestAccept(
        sessionId: SessionId,
        cmd: Command.QuestAccept,
    ) {
        val qs = requireSystemOrNull(sessionId, questSystem, "Quests", outbound) ?: return
        players.withPlayer(sessionId) { me ->
            val nameHintLower = cmd.nameHint.trim().lowercase()
            val roomMobIds = mobs.mobsInRoom(me.roomId).map { it.id.value }.toSet()
            val matchingQuest =
                questRegistry
                    .all()
                    .filter { quest ->
                        quest.name.lowercase().contains(nameHintLower) ||
                            quest.id.substringAfterLast(':').lowercase().contains(nameHintLower)
                    }.firstOrNull { quest -> quest.giverMobId in roomMobIds }
            if (matchingQuest == null) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "No quest-giver here offers a quest matching '${cmd.nameHint}'.",
                    ),
                )
            } else {
                outbound.sendIfError(sessionId, qs.acceptQuest(sessionId, matchingQuest.id))
            }
        }
    }

    private suspend fun handleQuestAcceptById(
        sessionId: SessionId,
        cmd: Command.QuestAcceptById,
    ) {
        val qs = requireSystemOrNull(sessionId, questSystem, "Quests", outbound) ?: return
        val me = players.get(sessionId) ?: return
        val quest = questRegistry.get(cmd.questId)
        if (quest == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Unknown quest '${cmd.questId}'."))
            return
        }
        val roomMobIds = mobs.mobsInRoom(me.roomId).map { it.id.value }.toSet()
        if (quest.giverMobId !in roomMobIds) {
            outbound.send(OutboundEvent.SendError(sessionId, "The quest-giver isn't here."))
            return
        }
        outbound.sendIfError(sessionId, qs.acceptQuest(sessionId, cmd.questId))
    }

    private suspend fun handleQuestTurnIn(
        sessionId: SessionId,
        cmd: Command.QuestTurnIn,
    ) {
        val qs = requireSystemOrNull(sessionId, questSystem, "Quests", outbound) ?: return
        val me = players.get(sessionId) ?: return
        val roomMobIds = mobs.mobsInRoom(me.roomId).map { it.id.value }
        // Resolve the quest from the hint up front so we can refresh offers
        // for the right NPC after a successful turn-in.
        val matched = me.activeQuests.keys
            .mapNotNull(questRegistry::get)
            .firstOrNull { quest ->
                val lower = cmd.nameHint.trim().lowercase()
                quest.name.lowercase().contains(lower) || quest.id.lowercase().contains(lower)
            }
        val err = qs.turnInQuest(sessionId, cmd.nameHint, roomMobIds)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
            return
        }
        refreshOffersAfterTurnIn(sessionId, qs, matched, roomMobIds)
    }

    private suspend fun handleQuestTurnInById(
        sessionId: SessionId,
        cmd: Command.QuestTurnInById,
    ) {
        val qs = requireSystemOrNull(sessionId, questSystem, "Quests", outbound) ?: return
        val me = players.get(sessionId) ?: return
        val roomMobIds = mobs.mobsInRoom(me.roomId).map { it.id.value }
        val quest = questRegistry.get(cmd.questId)
        val err = qs.turnInQuestById(sessionId, cmd.questId, roomMobIds)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
            return
        }
        refreshOffersAfterTurnIn(sessionId, qs, quest, roomMobIds)
    }

    /**
     * Re-emit `Quest.Available` for the resolved turn-in NPC after a successful
     * turn-in so an open QuestOfferPanel reflects the post-turn-in state
     * (otherwise it keeps showing the now-completed quest with its Turn In
     * button).
     */
    private suspend fun refreshOffersAfterTurnIn(
        sessionId: SessionId,
        qs: QuestSystem,
        quest: QuestDef?,
        roomMobIds: Collection<String>,
    ) {
        val turnInMobId = (quest?.turnInMobId ?: quest?.giverMobId) ?: return
        if (turnInMobId !in roomMobIds) return
        gmcpEmitter?.sendQuestAvailable(sessionId, qs.questOffersFor(sessionId, turnInMobId))
    }

    private suspend fun handleAchievementList(sessionId: SessionId) {
        val achievements = requireSystemOrNull(sessionId, achievementSystem, "Achievements", outbound) ?: return
        outbound.send(OutboundEvent.SendInfo(sessionId, achievements.formatAchievements(sessionId)))
    }

    private suspend fun handleTitleSet(
        sessionId: SessionId,
        cmd: Command.TitleSet,
    ) {
        val achievements = requireSystemOrNull(sessionId, achievementSystem, "Achievements", outbound) ?: return
        val available = achievements.availableTitles(sessionId)
        val match = available.firstOrNull { (_, title) -> title.equals(cmd.titleArg, ignoreCase = true) }
        if (match == null) {
            outbound.send(
                OutboundEvent.SendError(
                    sessionId,
                    "No title '${cmd.titleArg}' available. Use 'achievements' to see earned titles.",
                ),
            )
        } else {
            players.setDisplayTitle(sessionId, match.second)
            outbound.send(OutboundEvent.SendInfo(sessionId, "Title set to: ${match.second}"))
        }
    }

    private suspend fun handleTitleClear(sessionId: SessionId) {
        players.setDisplayTitle(sessionId, null)
        outbound.send(OutboundEvent.SendInfo(sessionId, "Title cleared."))
    }
}
