package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.PetSystem
import dev.ambon.engine.ceilSeconds
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import java.time.Clock

class PetHandler(
    ctx: EngineContext,
    private val petSystem: PetSystem,
    private val clock: Clock = Clock.systemUTC(),
) : CommandHandler {
    private val players = ctx.players
    private val mobs = ctx.mobs
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter
    private val combat: CombatSystem = ctx.combat

    override fun register(router: CommandRouter) {
        router.on<Command.PetStatus> { sid, _ -> handlePetStatus(sid) }
        router.on<Command.PetDismiss> { sid, _ -> handlePetDismiss(sid) }
        router.on<Command.PetName> { sid, cmd -> handlePetName(sid, cmd) }
        router.on<Command.PetSkills> { sid, _ -> handlePetSkills(sid) }
        router.on<Command.PetSkill> { sid, cmd -> handlePetSkill(sid, cmd) }
    }

    private suspend fun handlePetStatus(sessionId: SessionId) {
        val pet = petSystem.getActivePet(sessionId)
        if (pet == null) {
            val message = "You have no active pet. Summon one with a pet ability."
            outbound.send(OutboundEvent.SendInfo(sessionId, message))
            sendScopedFeedback(
                sessionId,
                gmcpEmitter,
                "info",
                message,
                "pet",
                code = "NO_ACTIVE_PET",
                command = "status",
            )
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
            val message = "You have no active pet."
            sendErrorWithFeedback(
                sessionId,
                outbound,
                gmcpEmitter,
                message,
                "pet",
                code = "NO_ACTIVE_PET",
                command = "dismiss",
            )
            return
        }

        val petName = pet.name
        val dismissed = petSystem.dismissAll(sessionId)
        val message = "You dismiss $petName."
        outbound.send(OutboundEvent.SendInfo(sessionId, message))
        sendScopedFeedback(
            sessionId,
            gmcpEmitter,
            "success",
            message,
            "pet",
            code = "PET_DISMISSED",
            command = "dismiss",
        )
        emitInactivePet(sessionId)
        players.withPlayer(sessionId) { me ->
            broadcastToRoom(me.roomId, "$petName vanishes.", players, outbound)
        }
        // Refresh the room mob list for the dismisser and bystanders so the pet disappears
        // immediately instead of lingering until next room change. See issue #1069.
        for (entry in dismissed) {
            broadcastPetRemoved(entry)
        }
    }

    private suspend fun broadcastPetRemoved(dismissed: PetSystem.DismissedPet) {
        val emitter = gmcpEmitter ?: return
        emitter.broadcastRoomRemoveMob(dismissed.roomId, dismissed.petId.value, players)
        val mobsInRoom = mobs.mobsInRoom(dismissed.roomId)
        val mobInfoEntries = emitter.buildMobInfoEntries(mobsInRoom)
        emitter.broadcastRoomMobInfo(dismissed.roomId, mobInfoEntries, players)
    }

    private suspend fun handlePetName(sessionId: SessionId, cmd: Command.PetName) {
        val pet = petSystem.getActivePet(sessionId)
        if (pet == null) {
            val message = "You have no active pet."
            sendErrorWithFeedback(
                sessionId,
                outbound,
                gmcpEmitter,
                message,
                "pet",
                code = "NO_ACTIVE_PET",
                command = "name",
            )
            return
        }

        val oldName = pet.name
        pet.name = cmd.newName
        val message = "You rename $oldName to ${cmd.newName}."
        outbound.send(OutboundEvent.SendInfo(sessionId, message))
        sendScopedFeedback(
            sessionId,
            gmcpEmitter,
            "success",
            message,
            "pet",
            code = "PET_RENAMED",
            command = "name",
        )
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
        emitPetSkillsForActive(sessionId, pet)
    }

    private suspend fun emitPetSkillsForActive(sessionId: SessionId, pet: dev.ambon.domain.mob.MobState) {
        val emitter = gmcpEmitter ?: return
        val now = clock.millis()
        val payloads = pet.spells.map { spell ->
            GmcpEmitter.PetSkillPayload(
                id = spell.id,
                name = spell.displayName,
                description = "",
                cooldownMs = spell.cooldownMs,
                cooldownRemainingMs = combat.petSkillCooldownRemainingMs(pet.id, spell.id, now),
                effectType = when {
                    spell.threatBonus > 0.0 && spell.damage == null -> "TAUNT"
                    spell.damage != null -> "DIRECT_DAMAGE"
                    spell.statusEffectId != null -> "APPLY_STATUS"
                    else -> "DIRECT_DAMAGE"
                },
                image = spell.image,
                petName = pet.name,
            )
        }
        emitter.sendPetSkills(sessionId, payloads)
    }

    private suspend fun handlePetSkills(sessionId: SessionId) {
        val pet = petSystem.getActivePet(sessionId)
        if (pet == null) {
            val message = "You have no active pet."
            sendErrorWithFeedback(
                sessionId,
                outbound,
                gmcpEmitter,
                message,
                "pet",
                code = "NO_ACTIVE_PET",
                command = "skills",
            )
            return
        }
        if (pet.spells.isEmpty()) {
            val message = "${pet.name} has no signature skills."
            outbound.send(OutboundEvent.SendInfo(sessionId, message))
            sendScopedFeedback(
                sessionId,
                gmcpEmitter,
                "info",
                message,
                "pet",
                code = "PET_NO_SKILLS",
                command = "skills",
            )
            return
        }
        val now = clock.millis()
        outbound.send(OutboundEvent.SendInfo(sessionId, "[ ${pet.name}'s skills ]"))
        for (skill in pet.spells) {
            val remaining = combat.petSkillCooldownRemainingMs(pet.id, skill.id, now)
            val cooldownText =
                if (remaining > 0L) " (${remaining.ceilSeconds()}s cooldown)" else " (ready)"
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  ${skill.id}: ${skill.displayName}$cooldownText",
                ),
            )
        }
        outbound.send(OutboundEvent.SendInfo(sessionId, "Use 'pet <skill>' to trigger one."))
    }

    private suspend fun handlePetSkill(sessionId: SessionId, cmd: Command.PetSkill) {
        when (val result = combat.triggerPetSkill(sessionId, cmd.skillQuery)) {
            CombatSystem.PetSkillResult.Ok -> {
                // CombatSystem already wrote the success messages and combat events.
                outbound.send(OutboundEvent.SendPrompt(sessionId))
            }
            is CombatSystem.PetSkillResult.Error -> {
                sendErrorWithFeedback(
                    sessionId,
                    outbound,
                    gmcpEmitter,
                    result.message,
                    "pet",
                    code = result.code,
                    command = "skill",
                )
            }
        }
    }

    private suspend fun emitInactivePet(sessionId: SessionId) {
        gmcpEmitter?.sendPetSkills(sessionId, emptyList())
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
