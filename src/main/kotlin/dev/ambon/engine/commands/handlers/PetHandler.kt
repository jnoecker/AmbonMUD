package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.PetSystem
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class PetHandler(
    ctx: EngineContext,
    private val petSystem: PetSystem,
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.PetStatus> { sid, _ -> handlePetStatus(sid) }
        router.on<Command.PetDismiss> { sid, _ -> handlePetDismiss(sid) }
        router.on<Command.PetName> { sid, cmd -> handlePetName(sid, cmd) }
    }

    private suspend fun handlePetStatus(sessionId: SessionId) {
        val pet = petSystem.getActivePet(sessionId)
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
        val pet = petSystem.getActivePet(sessionId)
        if (pet == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "You have no active pet."))
            return
        }

        val petName = pet.name
        petSystem.dismissAll(sessionId)
        outbound.send(OutboundEvent.SendInfo(sessionId, "You dismiss $petName."))
        emitInactivePet(sessionId)
        players.withPlayer(sessionId) { me ->
            broadcastToRoom(me.roomId, "$petName vanishes.", players, outbound)
        }
    }

    private suspend fun handlePetName(sessionId: SessionId, cmd: Command.PetName) {
        val pet = petSystem.getActivePet(sessionId)
        if (pet == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "You have no active pet."))
            return
        }

        val oldName = pet.name
        pet.name = cmd.newName
        outbound.send(OutboundEvent.SendInfo(sessionId, "You rename $oldName to ${cmd.newName}."))
        emitPetState(sessionId, pet)
    }

    private suspend fun emitPetState(sessionId: SessionId, pet: dev.ambon.domain.mob.MobState) {
        gmcpEmitter?.sendPetState(
            sessionId,
            GmcpEmitter.PetStatePayload(
                active = true,
                name = pet.name,
                hp = pet.hp,
                maxHp = pet.maxHp,
                minDamage = pet.damage.min,
                maxDamage = pet.damage.max,
                armor = pet.armor,
                image = pet.image,
            ),
        )
    }

    private suspend fun emitInactivePet(sessionId: SessionId) {
        gmcpEmitter?.sendPetState(
            sessionId,
            GmcpEmitter.PetStatePayload(
                active = false,
                name = null,
                hp = null,
                maxHp = null,
                minDamage = null,
                maxDamage = null,
                armor = null,
                image = null,
            ),
        )
    }
}
