package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.FriendsSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on

class FriendsHandler(
    ctx: EngineContext,
    private val friendsSystem: FriendsSystem? = null,
) : CommandHandler {
    private val outbound = ctx.outbound

    override fun register(router: CommandRouter) {
        router.on<Command.Friend.List> { sid, _ -> handleFriendCmd(sid, Command.Friend.List) }
        router.on<Command.Friend.Add> { sid, cmd -> handleFriendCmd(sid, cmd) }
        router.on<Command.Friend.Remove> { sid, cmd -> handleFriendCmd(sid, cmd) }
    }

    private suspend fun handleFriendCmd(
        sessionId: SessionId,
        cmd: Command.Friend,
    ) {
        val fs = requireSystemOrNull(sessionId, friendsSystem, "Friends", outbound) ?: return
        val err = when (cmd) {
            Command.Friend.List -> fs.listFriends(sessionId)
            is Command.Friend.Add -> fs.addFriend(sessionId, cmd.target)
            is Command.Friend.Remove -> fs.removeFriend(sessionId, cmd.target)
        }
        outbound.sendIfError(sessionId, err)
    }
}
