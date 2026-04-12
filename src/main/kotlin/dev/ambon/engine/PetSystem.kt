package dev.ambon.engine

import dev.ambon.config.PetConfig
import dev.ambon.config.PetSpellConfig
import dev.ambon.config.PetTemplateConfig
import dev.ambon.domain.DamageRange
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobSpell
import dev.ambon.domain.mob.MobState
import dev.ambon.engine.status.StatusEffectId
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.util.UUID

private val log = KotlinLogging.logger {}

/**
 * Manages summoned pets/companions.
 *
 * A pet is a friendly MobState with [MobState.ownerSessionId] set.
 * Pets follow their owner between rooms, attack the owner's combat
 * target, and expire when dismissed, duration expires, or owner
 * disconnects.
 */
class PetSystem(
    private val config: PetConfig,
    private val mobs: MobRegistry,
    private val clock: Clock,
) {
    /** Tracks expiry time for timed pets. Key = MobId, value = wall-clock ms when the pet expires. */
    private val expiryTimes = mutableMapOf<MobId, Long>()

    fun getTemplate(key: String): PetTemplateConfig? = config.definitions[key]

    fun allTemplates(): Map<String, PetTemplateConfig> = config.definitions

    /** Returns the active pet for a player, or null. */
    fun getActivePet(ownerSid: SessionId): MobState? =
        mobs.all().firstOrNull { it.ownerSessionId == ownerSid }

    /** Returns all active pets for a player. */
    fun getPets(ownerSid: SessionId): List<MobState> =
        mobs.all().filter { it.ownerSessionId == ownerSid }

    /**
     * Summons a pet from a template. Dismisses any existing pet first.
     * [durationMs] of 0 means permanent (no automatic expiry).
     * Returns the new pet MobState, or null if the template doesn't exist.
     */
    fun summon(
        ownerSid: SessionId,
        templateKey: String,
        roomId: RoomId,
        ownerLevel: Int,
        durationMs: Long = 0L,
        ownerName: String? = null,
    ): MobState? {
        val template = config.definitions[templateKey] ?: return null

        // Dismiss existing pet
        dismissAll(ownerSid)

        // Scale pet stats with owner level
        val levelScale = 1.0 + (ownerLevel - 1) * 0.1
        val scaledHp = (template.hp * levelScale).toInt().coerceAtLeast(1)
        val scaledMinDmg = (template.minDamage * levelScale).toInt().coerceAtLeast(1)
        val scaledMaxDmg = (template.maxDamage * levelScale).toInt().coerceAtLeast(1)

        val petId = MobId("pet:${UUID.randomUUID().toString().take(8)}")
        val petSpells = template.spells.map { (key, sc) -> toMobSpell(key, sc) }
        val pet = MobState(
            id = petId,
            name = template.name,
            description = template.description,
            roomId = roomId,
            hp = scaledHp,
            maxHp = scaledHp,
            damage = DamageRange(scaledMinDmg, scaledMaxDmg),
            armor = template.armor,
            xpReward = 0L,
            templateKey = templateKey,
            image = template.image,
            ownerSessionId = ownerSid,
            ownerName = ownerName,
            spells = petSpells,
            defaultAttack = template.defaultAttack,
        )

        mobs.upsert(pet)
        if (durationMs > 0L) {
            expiryTimes[petId] = clock.millis() + durationMs
        }
        val durationLabel = if (durationMs > 0L) "${durationMs}ms" else "permanent"
        log.debug { "Pet summoned: ${pet.name} (${pet.id}) for owner $ownerSid, duration=$durationLabel" }
        return pet
    }

    /**
     * Checks for pets whose duration has expired.
     * Returns a list of (ownerSessionId, petName) pairs for expired pets so the caller can notify owners.
     */
    fun tick(): List<ExpiredPet> {
        val now = clock.millis()
        val expired = mutableListOf<ExpiredPet>()
        val iterator = expiryTimes.iterator()
        while (iterator.hasNext()) {
            val (mobId, expiresAt) = iterator.next()
            if (now >= expiresAt) {
                val pet = mobs.get(mobId)
                if (pet != null) {
                    expired.add(ExpiredPet(pet.ownerSessionId!!, pet.name))
                    mobs.remove(mobId)
                }
                iterator.remove()
            }
        }
        return expired
    }

    /** Dismisses all pets owned by a player. Returns dismissed pet count. */
    fun dismissAll(ownerSid: SessionId): Int {
        val pets = getPets(ownerSid)
        for (pet in pets) {
            expiryTimes.remove(pet.id)
            mobs.remove(pet.id)
            log.debug { "Pet dismissed: ${pet.name} (${pet.id})" }
        }
        return pets.size
    }

    /** Moves all pets to follow their owner to a new room. */
    fun followOwner(ownerSid: SessionId, newRoomId: RoomId) {
        for (pet in getPets(ownerSid)) {
            mobs.moveTo(pet.id, newRoomId)
        }
    }

    /** Called on owner disconnect — dismisses all pets. */
    fun onOwnerDisconnect(ownerSid: SessionId) {
        dismissAll(ownerSid)
    }

    fun clear() {
        expiryTimes.clear()
        for (mob in mobs.all().filter { it.isPet }) {
            mobs.remove(mob.id)
        }
    }

    private fun toMobSpell(key: String, sc: PetSpellConfig): MobSpell =
        MobSpell(
            id = key,
            displayName = sc.displayName,
            message = sc.message,
            roomMessage = sc.roomMessage,
            damage = if (sc.minDamage != null || sc.maxDamage != null) {
                DamageRange(sc.minDamage ?: 1, sc.maxDamage ?: (sc.minDamage ?: 1))
            } else {
                null
            },
            healMin = sc.healMin,
            healMax = sc.healMax,
            statusEffectId = sc.statusEffectId?.let { StatusEffectId(it) },
            cooldownMs = sc.cooldownMs,
            weight = sc.weight,
        )

    data class ExpiredPet(
        val ownerSessionId: SessionId,
        val petName: String,
    )
}
