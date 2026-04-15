package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.AchievementRegistry
import dev.ambon.engine.AchievementSystem
import dev.ambon.engine.HouseEntryResult
import dev.ambon.engine.HousingSystem
import dev.ambon.engine.QuestAvailableEntry
import dev.ambon.engine.QuestAvailableObjectiveSummary
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
    private val world = ctx.world
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.Talk> { sid, cmd -> handleTalk(sid, cmd) }
        router.on<Command.DialogueChoice> { sid, cmd -> handleDialogueChoice(sid, cmd) }
        router.on<Command.QuestLog> { sid, _ -> handleQuestLog(sid) }
        router.on<Command.QuestInfo> { sid, cmd -> handleQuestInfo(sid, cmd) }
        router.on<Command.QuestAbandon> { sid, cmd -> handleQuestAbandon(sid, cmd) }
        router.on<Command.QuestAccept> { sid, cmd -> handleQuestAccept(sid, cmd) }
        router.on<Command.AchievementList> { sid, _ -> handleAchievementList(sid) }
        router.on<Command.TitleSet> { sid, cmd -> handleTitleSet(sid, cmd) }
        router.on<Command.TitleClear> { sid, _ -> handleTitleClear(sid) }
    }

    private suspend fun handleTalk(
        sessionId: SessionId,
        cmd: Command.Talk,
    ) {
        val me = players.get(sessionId)
        if (dialogueSystem == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Nobody here wants to talk."))
            return
        }
        outbound.sendIfError(sessionId, dialogueSystem.startConversation(sessionId, cmd.target))
        if (questSystem != null && me != null) {
            val mob = mobs.findInRoomByKeyword(me.roomId, cmd.target.trim()).firstOrNull()
            if (mob != null) {
                val available = questSystem.availableQuests(sessionId, mob.id.value)
                for (quest in available) {
                    outbound.send(OutboundEvent.SendText(sessionId, "[Quest] ${quest.name} — ${quest.description}"))
                    outbound.send(OutboundEvent.SendText(sessionId, "  Type 'accept ${quest.name}' to accept."))
                }
                for (quest in questSystem.hintedQuests(sessionId, mob.id.value)) {
                    outbound.send(
                        OutboundEvent.SendText(
                            sessionId,
                            "[Quest] ${quest.name} — your standing is too low to take this on yet.",
                        ),
                    )
                }
                gmcpEmitter?.sendQuestAvailable(
                    sessionId,
                    available.map { quest ->
                        QuestAvailableEntry(
                            id = quest.id,
                            name = quest.name,
                            description = quest.description,
                            giverMobId = quest.giverMobId,
                            objectives = quest.objectives.map { obj ->
                                QuestAvailableObjectiveSummary(
                                    description = obj.description,
                                    count = obj.count,
                                )
                            },
                            rewardXp = quest.rewards.xp,
                            rewardGold = quest.rewards.gold,
                        )
                    },
                )
            }
        }
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

    private suspend fun handleDialogueAction(
        sessionId: SessionId,
        action: String,
    ) {
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
