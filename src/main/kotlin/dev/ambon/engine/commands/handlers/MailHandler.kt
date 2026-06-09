package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.items.ItemInstance
import dev.ambon.domain.mail.MailMessage
import dev.ambon.engine.PlayerState
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

class MailHandler(
    ctx: EngineContext,
    private val clock: Clock = Clock.systemUTC(),
    private val markVitalsDirty: (SessionId) -> Unit = {},
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter
    private val metrics = ctx.metrics
    private val items = ctx.items

    override fun register(router: CommandRouter) {
        router.on<Command.Mail.List> { sid, _ -> handleList(sid) }
        router.on<Command.Mail.Read> { sid, cmd -> handleRead(sid, cmd) }
        router.on<Command.Mail.Delete> { sid, cmd -> handleDelete(sid, cmd) }
        router.on<Command.Mail.Send> { sid, cmd -> handleSend(sid, cmd) }
        router.on<Command.Mail.Claim> { sid, cmd -> handleClaim(sid, cmd) }
        router.on<Command.Mail.Abort> { sid, _ -> handleAbort(sid) }
    }

    /** Called by the engine before command routing when the player has an active compose state. */
    suspend fun handleComposeLine(
        sessionId: SessionId,
        line: String,
    ) {
        val ps = players.get(sessionId) ?: return
        val compose = ps.mailCompose ?: return

        if (line.trim() == ".") {
            finishCompose(sessionId, ps, compose)
        } else {
            compose.lines.add(line)
            outbound.send(OutboundEvent.SendInfo(sessionId, "> "))
        }
    }

    private suspend fun handleList(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            gmcpEmitter?.sendMailList(sessionId, me.inbox)
            if (me.inbox.isEmpty()) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "Your inbox is empty."))
                return@withPlayer
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "-- Inbox (${me.inbox.size} message(s)) --"))
            me.inbox.forEachIndexed { index, msg ->
                val marker = if (!msg.read) "[NEW] " else "      "
                val gift = if (msg.hasUnclaimedAttachments) "[GIFT] " else ""
                val date = java.time.Instant.ofEpochMilli(msg.sentAtEpochMs)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                outbound.send(
                    OutboundEvent.SendInfo(sessionId, "${index + 1}. $marker${gift}From: ${msg.fromName}  ($date)"),
                )
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "Type 'mail read <n>' to read a message."))
        }
    }

    private suspend fun handleRead(
        sessionId: SessionId,
        cmd: Command.Mail.Read,
    ) {
        players.withPlayer(sessionId) { me ->
            val msg = me.inbox.getOrNull(cmd.index - 1)
            if (msg == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "No message at index ${cmd.index}."))
                return@withPlayer
            }
            val date = java.time.Instant.ofEpochMilli(msg.sentAtEpochMs).atZone(ZoneOffset.UTC)
            outbound.send(OutboundEvent.SendInfo(sessionId, "-- Message ${cmd.index} --"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "From : ${msg.fromName}"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "Sent : $date"))
            outbound.send(OutboundEvent.SendInfo(sessionId, ""))
            outbound.send(OutboundEvent.SendInfo(sessionId, msg.body))
            outbound.send(OutboundEvent.SendInfo(sessionId, "-- End of message --"))

            if (msg.gold > 0L || msg.item != null) {
                val parts = buildList {
                    if (msg.gold > 0L) add("${msg.gold} gold")
                    msg.item?.let { add(it.item.displayName) }
                }
                val state = if (msg.claimed) "(claimed)" else "(unclaimed — 'mail claim ${cmd.index}')"
                outbound.send(
                    OutboundEvent.SendInfo(sessionId, "Attached: ${parts.joinToString(" and ")} $state"),
                )
            }

            if (!msg.read) {
                me.inbox[cmd.index - 1] = msg.copy(read = true)
                players.persistPlayer(sessionId)
                gmcpEmitter?.sendMailList(sessionId, me.inbox)
            }
            gmcpEmitter?.sendMailMessage(sessionId, cmd.index, me.inbox[cmd.index - 1])
        }
    }

    private suspend fun handleDelete(
        sessionId: SessionId,
        cmd: Command.Mail.Delete,
    ) {
        players.withPlayer(sessionId) { me ->
            if (cmd.index < 1 || cmd.index > me.inbox.size) {
                outbound.send(OutboundEvent.SendError(sessionId, "No message at index ${cmd.index}."))
                return@withPlayer
            }
            if (me.inbox[cmd.index - 1].hasUnclaimedAttachments) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "Claim the gift on message ${cmd.index} before deleting it (mail claim ${cmd.index}).",
                    ),
                )
                return@withPlayer
            }
            me.inbox.removeAt(cmd.index - 1)
            outbound.send(OutboundEvent.SendInfo(sessionId, "Message ${cmd.index} deleted."))
            players.persistPlayer(sessionId)
            gmcpEmitter?.sendMailList(sessionId, me.inbox)
        }
    }

    private suspend fun handleSend(
        sessionId: SessionId,
        cmd: Command.Mail.Send,
    ) {
        if (!requireClaimed(sessionId, players, outbound, "Sending mail", gmcpEmitter, "mail", "send")) return
        players.withPlayer(sessionId) { me ->
            if (me.mailCompose != null) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You are already composing a message. Type '.' to send or 'mail abort' to cancel.",
                    ),
                )
                return@withPlayer
            }
            // Up-front attachment checks for fast feedback; re-validated on send.
            if (cmd.gold > 0L && me.gold < cmd.gold) {
                outbound.send(
                    OutboundEvent.SendError(sessionId, "You only have ${me.gold} gold to attach."),
                )
                return@withPlayer
            }
            if (cmd.itemKeyword != null && items.peekOwnedItem(sessionId, cmd.itemKeyword) == null) {
                outbound.send(
                    OutboundEvent.SendError(sessionId, "You aren't carrying '${cmd.itemKeyword}'."),
                )
                return@withPlayer
            }
            me.mailCompose = PlayerState.MailComposeState(
                recipientName = cmd.recipientName,
                attachGold = cmd.gold,
                attachItemKeyword = cmd.itemKeyword,
            )
            outbound.send(OutboundEvent.SendInfo(sessionId, "Composing mail to ${cmd.recipientName}."))
            if (cmd.gold > 0L || cmd.itemKeyword != null) {
                val parts = buildList {
                    if (cmd.gold > 0L) add("${cmd.gold} gold")
                    cmd.itemKeyword?.let { add("item '$it'") }
                }
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "Attaching ${parts.joinToString(" and ")} (consumed when you send).",
                    ),
                )
            }
            outbound.send(
                OutboundEvent.SendInfo(sessionId, "Enter your message line by line. Type '.' alone to send, or 'mail abort' to cancel."),
            )
            outbound.send(OutboundEvent.SendInfo(sessionId, "> "))
        }
    }

    private suspend fun handleAbort(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            if (me.mailCompose == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You are not composing a message."))
                return@withPlayer
            }
            me.mailCompose = null
            outbound.send(OutboundEvent.SendInfo(sessionId, "Message composition cancelled."))
        }
    }

    private suspend fun finishCompose(
        sessionId: SessionId,
        sender: PlayerState,
        compose: PlayerState.MailComposeState,
    ) {
        sender.mailCompose = null

        if (compose.lines.isEmpty()) {
            outbound.send(OutboundEvent.SendError(sessionId, "Message is empty. Mail not sent."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }

        // Validate, then consume the attachments. The item is removed first so it
        // can be handed back cleanly if anything downstream fails.
        if (compose.attachGold > 0L && sender.gold < compose.attachGold) {
            outbound.send(
                OutboundEvent.SendError(sessionId, "You no longer have ${compose.attachGold} gold. Mail not sent."),
            )
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        var attachedItem: ItemInstance? = null
        if (compose.attachItemKeyword != null) {
            val removed = items.removeFromInventory(sessionId, compose.attachItemKeyword)
            if (removed == null) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You aren't carrying '${compose.attachItemKeyword}' anymore. Mail not sent.",
                    ),
                )
                outbound.send(OutboundEvent.SendPrompt(sessionId))
                return
            }
            if (removed.item.questItem) {
                items.addToInventory(sessionId, removed)
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You can't mail ${removed.item.displayName} — it's a quest item. Mail not sent.",
                    ),
                )
                outbound.send(OutboundEvent.SendPrompt(sessionId))
                return
            }
            attachedItem = removed
        }
        if (compose.attachGold > 0L) {
            sender.gold -= compose.attachGold
            markVitalsDirty(sessionId)
        }
        attachedItem?.let { gmcpEmitter?.sendCharItemsRemove(sessionId, it) }

        val message = MailMessage(
            id = UUID.randomUUID().toString(),
            fromName = sender.name,
            body = compose.lines.joinToString("\n"),
            sentAtEpochMs = clock.millis(),
            gold = compose.attachGold,
            item = attachedItem,
        )
        val attachNote = attachmentSummary(message.gold, message.item)?.let { " (with $it)" } ?: ""

        val recipientOnline = players.getByName(compose.recipientName)
        if (recipientOnline != null) {
            recipientOnline.inbox.add(message)
            players.persistPlayer(recipientOnline.sessionId)
            if (message.gold > 0L || message.item != null) players.persistPlayer(sessionId)
            metrics.onGameEvent("mail", "sent")
            outbound.send(OutboundEvent.SendInfo(sessionId, "Mail delivered to ${recipientOnline.name}$attachNote."))
            if (recipientOnline.sessionId != sessionId) {
                val gift = if (message.hasUnclaimedAttachments) " (with a gift!)" else ""
                outbound.send(
                    OutboundEvent.SendInfo(
                        recipientOnline.sessionId,
                        "You have new mail from ${sender.name}$gift. Type 'mail' to read it.",
                    ),
                )
                val unread = recipientOnline.inbox.count { !it.read }
                gmcpEmitter?.sendMailNotification(recipientOnline.sessionId, sender.name, unread)
                gmcpEmitter?.sendMailList(recipientOnline.sessionId, recipientOnline.inbox)
            }
        } else {
            val delivered = players.deliverMailOffline(compose.recipientName, message)
            if (!delivered) {
                refundAttachments(sessionId, sender, message.gold, attachedItem)
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "No player named '${compose.recipientName}' found. Mail not sent.",
                    ),
                )
                outbound.send(OutboundEvent.SendPrompt(sessionId))
                return
            }
            if (message.gold > 0L || message.item != null) players.persistPlayer(sessionId)
            metrics.onGameEvent("mail", "sent")
            outbound.send(OutboundEvent.SendInfo(sessionId, "Mail delivered to ${compose.recipientName}$attachNote."))
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleClaim(
        sessionId: SessionId,
        cmd: Command.Mail.Claim,
    ) {
        players.withPlayer(sessionId) { me ->
            val msg = me.inbox.getOrNull(cmd.index - 1)
            if (msg == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "No message at index ${cmd.index}."))
                return@withPlayer
            }
            if (!msg.hasUnclaimedAttachments) {
                outbound.send(OutboundEvent.SendError(sessionId, "There is nothing to claim on message ${cmd.index}."))
                return@withPlayer
            }

            if (msg.gold > 0L) {
                me.gold += msg.gold
                markVitalsDirty(sessionId)
            }
            msg.item?.let { instance ->
                items.addToInventory(sessionId, instance)
                gmcpEmitter?.sendCharItemsAdd(sessionId, instance)
            }
            me.inbox[cmd.index - 1] = msg.copy(claimed = true)
            players.persistPlayer(sessionId)
            metrics.onGameEvent("mail", "claimed")

            val summary = attachmentSummary(msg.gold, msg.item) ?: "your gift"
            outbound.send(OutboundEvent.SendInfo(sessionId, "You claim $summary from ${msg.fromName}'s letter."))
            gmcpEmitter?.sendMailList(sessionId, me.inbox)
            gmcpEmitter?.sendMailMessage(sessionId, cmd.index, me.inbox[cmd.index - 1])
        }
    }

    /** Hands consumed attachments back to the sender (used when delivery fails). */
    private suspend fun refundAttachments(
        sessionId: SessionId,
        sender: PlayerState,
        gold: Long,
        item: ItemInstance?,
    ) {
        if (gold > 0L) {
            sender.gold += gold
            markVitalsDirty(sessionId)
        }
        if (item != null) {
            items.addToInventory(sessionId, item)
            gmcpEmitter?.sendCharItemsAdd(sessionId, item)
        }
    }

    /** "100 gold and a Rusty Sword", or null when there is nothing attached. */
    private fun attachmentSummary(gold: Long, item: ItemInstance?): String? {
        val parts = buildList {
            if (gold > 0L) add("$gold gold")
            item?.let { add(it.item.displayName) }
        }
        return if (parts.isEmpty()) null else parts.joinToString(" and ")
    }
}
