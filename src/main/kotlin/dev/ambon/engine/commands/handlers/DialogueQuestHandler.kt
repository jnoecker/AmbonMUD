package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.AchievementRegistry
import dev.ambon.engine.AchievementSystem
import dev.ambon.engine.QuestRegistry
import dev.ambon.engine.QuestSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.dialogue.DialogueSystem
import dev.ambon.engine.events.OutboundEvent

class DialogueQuestHandler(
    router: CommandRouter,
    ctx: EngineContext,
    private val dialogueSystem: DialogueSystem? = null,
    private val questSystem: QuestSystem? = null,
    private val questRegistry: QuestRegistry = QuestRegistry(),
    private val achievementSystem: AchievementSystem? = null,
    private val achievementRegistry: AchievementRegistry = AchievementRegistry(),
) {
    private val players = ctx.players
    private val mobs = ctx.mobs
    private val outbound = ctx.outbound

    init {
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
        val err = dialogueSystem.startConversation(sessionId, cmd.target)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
        }
        if (questSystem != null && me != null) {
            val targetLower = cmd.target.trim().lowercase()
            val mob = mobs.mobsInRoom(me.roomId).firstOrNull { m -> m.name.lowercase().contains(targetLower) }
            if (mob != null) {
                val available = questSystem.availableQuests(sessionId, mob.id.value)
                for (quest in available) {
                    outbound.send(OutboundEvent.SendText(sessionId, "[Quest] ${quest.name} — ${quest.description}"))
                    outbound.send(OutboundEvent.SendText(sessionId, "  Type 'accept ${quest.name}' to accept."))
                }
            }
        }
    }

    private suspend fun handleDialogueChoice(
        sessionId: SessionId,
        cmd: Command.DialogueChoice,
    ) {
        if (dialogueSystem?.isInConversation(sessionId) != true) {
            outbound.send(OutboundEvent.SendText(sessionId, "Huh?"))
            return
        }
        val err = dialogueSystem.selectChoice(sessionId, cmd.optionNumber)
        if (err != null) {
            outbound.send(OutboundEvent.SendError(sessionId, err))
        }
    }

    private suspend fun handleQuestLog(sessionId: SessionId) {
        val log = questSystem?.formatQuestLog(sessionId) ?: "Quest system is not available."
        outbound.send(OutboundEvent.SendInfo(sessionId, log))
    }

    private suspend fun handleQuestInfo(
        sessionId: SessionId,
        cmd: Command.QuestInfo,
    ) {
        val info = questSystem?.formatQuestInfo(sessionId, cmd.nameHint) ?: "Quest system is not available."
        outbound.send(OutboundEvent.SendInfo(sessionId, info))
    }

    private suspend fun handleQuestAbandon(
        sessionId: SessionId,
        cmd: Command.QuestAbandon,
    ) {
        if (questSystem == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Quest system is not available."))
        } else {
            val err = questSystem.abandonQuest(sessionId, cmd.nameHint)
            if (err != null) outbound.send(OutboundEvent.SendError(sessionId, err))
        }
    }

    private suspend fun handleQuestAccept(
        sessionId: SessionId,
        cmd: Command.QuestAccept,
    ) {
        if (questSystem == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Quest system is not available."))
            return
        }
        val me = players.get(sessionId) ?: return
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
            val err = questSystem.acceptQuest(sessionId, matchingQuest.id)
            if (err != null) outbound.send(OutboundEvent.SendError(sessionId, err))
        }
    }

    private suspend fun handleAchievementList(sessionId: SessionId) {
        val list = achievementSystem?.formatAchievements(sessionId) ?: "Achievement system is not available."
        outbound.send(OutboundEvent.SendInfo(sessionId, list))
    }

    private suspend fun handleTitleSet(
        sessionId: SessionId,
        cmd: Command.TitleSet,
    ) {
        if (achievementSystem == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Achievement system is not available."))
            return
        }
        val available = achievementSystem.availableTitles(sessionId)
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
