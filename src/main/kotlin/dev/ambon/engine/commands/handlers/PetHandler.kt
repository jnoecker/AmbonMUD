package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.PetSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class PetHandler(
    ctx: EngineContext,
    private val petSystem: PetSystem? = null,
) : CommandHandler {
    private val players = ctx.players
    private val mobs = ctx.mobs
    private val outbound = ctx.outbound

    override fun register(router: CommandRouter) {
        router.on<Command.PetStatus> { sid, _ -> handlePetStatus(sid) }
        router.on<Command.PetDismiss> { sid, _ -> handlePetDismiss(sid) }
        router.on<Command.PetName> { sid, cmd -> handlePetName(sid, cmd) }
    }

    private suspend fun handlePetStatus(sessionId: SessionId) {
        val ps = petSystem
        if (ps == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "Pets are not available."))
            return
        }

        val pet = ps.getActivePet(sessionId)
        if (pet == null) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You have no active pet. Summon one with a pet ability."))
            return
        }

        outbound.send(OutboundEvent.SendInfo(sessionId, "[ Your Pet ]"))
        outbound.send(OutboundEvent.SendInfo(sessionId, "  Name: ${pet.name}"))
        outbound.send(OutboundEvent.SendInfo(sessionId, "  HP: ${pet.hp}/${pet.maxHp}"))
        outbound.send(
            OutboundEvent.SendInfo(sessionId, "  Damage: ${pet.damage.min}-${pet.damage.max}  Armor: ${pet.armor}"),
        )
        if (pet.description.isNotEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "  ${pet.description}"))
        }
    }

    private suspend fun handlePetDismiss(sessionId: SessionId) {
        val ps = petSystem
        if (ps == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "Pets are not available."))
            return
        }

        val pet = ps.getActivePet(sessionId)
        if (pet == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "You have no active pet."))
            return
        }

        val petName = pet.name
        ps.dismissAll(sessionId)
        outbound.send(OutboundEvent.SendInfo(sessionId, "You dismiss $petName."))
        players.withPlayer(sessionId) { me ->
            broadcastToRoom(me.roomId, "$petName vanishes.", players, outbound)
        }
    }

    private suspend fun handlePetName(sessionId: SessionId, cmd: Command.PetName) {
        val ps = petSystem ?: return

        val pet = ps.getActivePet(sessionId)
        if (pet == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "You have no active pet."))
            return
        }

        val oldName = pet.name
        pet.name = cmd.newName
        outbound.send(OutboundEvent.SendInfo(sessionId, "You rename $oldName to ${cmd.newName}."))
    }
}
