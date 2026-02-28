package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
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
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound

    override fun register(router: CommandRouter) {
        router.on<Command.Mail.List> { sid, _ -> handleList(sid) }
        router.on<Command.Mail.Read> { sid, cmd -> handleRead(sid, cmd) }
        router.on<Command.Mail.Delete> { sid, cmd -> handleDelete(sid, cmd) }
        router.on<Command.Mail.Send> { sid, cmd -> handleSend(sid, cmd) }
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
            if (me.inbox.isEmpty()) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "Your inbox is empty."))
                return@withPlayer
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "-- Inbox (${me.inbox.size} message(s)) --"))
            me.inbox.forEachIndexed { index, msg ->
                val marker = if (!msg.read) "[NEW] " else "      "
                val date = java.time.Instant.ofEpochMilli(msg.sentAtEpochMs)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                outbound.send(OutboundEvent.SendInfo(sessionId, "${index + 1}. $marker From: ${msg.fromName}  ($date)"))
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

            if (!msg.read) {
                me.inbox[cmd.index - 1] = msg.copy(read = true)
                players.persistPlayer(sessionId)
            }
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
            me.inbox.removeAt(cmd.index - 1)
            outbound.send(OutboundEvent.SendInfo(sessionId, "Message ${cmd.index} deleted."))
            players.persistPlayer(sessionId)
        }
    }

    private suspend fun handleSend(
        sessionId: SessionId,
        cmd: Command.Mail.Send,
    ) {
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
            me.mailCompose = PlayerState.MailComposeState(recipientName = cmd.recipientName)
            outbound.send(OutboundEvent.SendInfo(sessionId, "Composing mail to ${cmd.recipientName}."))
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

        val message = MailMessage(
            id = UUID.randomUUID().toString(),
            fromName = sender.name,
            body = compose.lines.joinToString("\n"),
            sentAtEpochMs = clock.millis(),
        )

        val recipientOnline = players.getByName(compose.recipientName)
        if (recipientOnline != null) {
            recipientOnline.inbox.add(message)
            players.persistPlayer(recipientOnline.sessionId)
            outbound.send(OutboundEvent.SendInfo(sessionId, "Mail delivered to ${recipientOnline.name}."))
            if (recipientOnline.sessionId != sessionId) {
                outbound.send(
                    OutboundEvent.SendInfo(
                        recipientOnline.sessionId,
                        "You have new mail from ${sender.name}. Type 'mail' to read it.",
                    ),
                )
            }
        } else {
            val delivered = players.deliverMailOffline(compose.recipientName, message)
            if (!delivered) {
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "No player named '${compose.recipientName}' found. Mail not sent.",
                    ),
                )
                outbound.send(OutboundEvent.SendPrompt(sessionId))
                return
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "Mail delivered to ${compose.recipientName}."))
        }
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }
}
