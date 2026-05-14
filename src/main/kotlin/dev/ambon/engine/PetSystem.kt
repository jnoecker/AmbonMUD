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
    imagesBaseUrl: String = "/images/",
) {
    /** Trailing-slash-normalized base for resolving pet template image paths. */
    private val imagesBase: String = if (imagesBaseUrl.endsWith("/")) imagesBaseUrl else "$imagesBaseUrl/"

    /** Tracks expiry time for timed pets. Key = MobId, value = wall-clock ms when the pet expires. */
    private val expiryTimes = mutableMapOf<MobId, Long>()

    // Synthetic session IDs let tank pets participate in the threat table.
    // Real player SessionIds are positive; pet SessionIds use a negative counter to avoid collisions.
    private var nextPetSessionId = Long.MIN_VALUE / 2
    private val petToSession = mutableMapOf<MobId, SessionId>()
    private val sessionToPet = mutableMapOf<SessionId, MobId>()

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
            image = resolveImage(template.image),
            ownerSessionId = ownerSid,
            ownerName = ownerName,
            spells = petSpells,
            defaultAttack = template.defaultAttack,
            threatMultiplier = template.threatMultiplier,
        )

        mobs.upsert(pet)

        // Assign a synthetic SessionId so the pet can participate in the threat table.
        if (template.threatMultiplier > 0.0) {
            val syntheticSid = SessionId(nextPetSessionId--)
            petToSession[petId] = syntheticSid
            sessionToPet[syntheticSid] = petId
        }
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
                    expired.add(ExpiredPet(pet.ownerSessionId!!, pet.name, mobId, pet.roomId))
                    removePetSession(mobId)
                    mobs.remove(mobId)
                }
                iterator.remove()
            }
        }
        return expired
    }

    /**
     * Dismisses all pets owned by a player. Returns the dismissed pets so callers can
     * broadcast `Room.RemoveMob` / refreshed `Room.MobInfo` to the rooms they were in
     * (issue #1069 — dismissed pet must disappear immediately, not on next room change).
     */
    fun dismissAll(ownerSid: SessionId): List<DismissedPet> {
        val pets = getPets(ownerSid)
        val dismissed = pets.map { DismissedPet(it.id, it.roomId, it.name) }
        for (pet in pets) {
            expiryTimes.remove(pet.id)
            removePetSession(pet.id)
            mobs.remove(pet.id)
            log.debug { "Pet dismissed: ${pet.name} (${pet.id})" }
        }
        if (pets.isNotEmpty()) lastManualSkillCastMs.remove(ownerSid)
        return dismissed
    }

    /** Moves all pets to follow their owner to a new room. */
    fun followOwner(ownerSid: SessionId, newRoomId: RoomId) {
        for (pet in getPets(ownerSid)) {
            mobs.moveTo(pet.id, newRoomId)
        }
    }

    /**
     * Called on owner disconnect — dismisses all pets. Returns the dismissed pets so the
     * caller can broadcast `Room.RemoveMob` to anyone left in their rooms (issue #1069).
     */
    fun onOwnerDisconnect(ownerSid: SessionId): List<DismissedPet> = dismissAll(ownerSid)

    fun clear() {
        expiryTimes.clear()
        petToSession.clear()
        sessionToPet.clear()
        lastManualSkillCastMs.clear()
        for (mob in mobs.all().filter { it.isPet }) {
            mobs.remove(mob.id)
        }
    }

    /** Returns the pet MobState for a synthetic SessionId, or null if not a pet session. */
    fun getPetBySession(sid: SessionId): MobState? {
        val mobId = sessionToPet[sid] ?: return null
        return mobs.get(mobId)
    }

    /** Returns true if [sid] is a synthetic pet session. */
    fun isPetSession(sid: SessionId): Boolean = sessionToPet.containsKey(sid)

    /** Returns the synthetic SessionId for a pet, or null if the pet has no threat entry. */
    fun getPetSessionId(petId: MobId): SessionId? = petToSession[petId]

    /** Removes a single pet by MobId (e.g. on pet death). Returns the dismissed pet, or null if not found. */
    fun dismissOne(petId: MobId): DismissedPet? {
        val pet = mobs.get(petId) ?: return null
        val dismissed = DismissedPet(pet.id, pet.roomId, pet.name)
        expiryTimes.remove(petId)
        removePetSession(petId)
        mobs.remove(petId)
        log.debug { "Pet dismissed: ${pet.name} ($petId)" }
        return dismissed
    }

    private fun removePetSession(petId: MobId) {
        val sid = petToSession.remove(petId)
        if (sid != null) sessionToPet.remove(sid)
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
            threatBonus = sc.threatBonus,
            image = sc.image,
        )

    /** Per-owner timestamp of the most recent manual `pet <skill>` trigger. */
    private val lastManualSkillCastMs = mutableMapOf<SessionId, Long>()

    /** Records that the owner just manually triggered a pet skill. */
    fun recordManualSkillCast(ownerSid: SessionId, now: Long) {
        lastManualSkillCastMs[ownerSid] = now
    }

    /**
     * Returns true if the owner triggered a pet skill within [PetConfig.manualSkillGraceMs] ms.
     * While true, the pet attack phase should NOT auto-cast skills — the player is driving the rotation.
     */
    fun isManualGraceActive(ownerSid: SessionId, now: Long): Boolean {
        val last = lastManualSkillCastMs[ownerSid] ?: return false
        return now - last < config.manualSkillGraceMs
    }

    /**
     * Looks up a skill on [pet] by case-insensitive id match, or by displayName prefix.
     * Returns null if no skill matches.
     */
    fun findSkill(pet: MobState, query: String): MobSpell? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        return pet.spells.firstOrNull { it.id.equals(q, ignoreCase = true) }
            ?: pet.spells.firstOrNull { it.displayName.lowercase().startsWith(q) }
            ?: pet.spells.firstOrNull { it.displayName.lowercase().contains(q) }
    }

    data class ExpiredPet(
        val ownerSessionId: SessionId,
        val petName: String,
        val petId: MobId,
        val roomId: RoomId,
    )

    /** Snapshot of a pet that was just removed — used by callers to broadcast room updates. */
    data class DismissedPet(
        val petId: MobId,
        val roomId: RoomId,
        val petName: String,
    )

    /**
     * Resolve a pet template's image path against the configured CDN/local base, mirroring
     * how mob images are prefixed in `WorldLoader`. Bare relative paths previously slipped
     * through and resolved against the MUD host instead of the asset host (issue #1068).
     * Already-absolute URLs (`http(s)://…`) and explicit roots (`/…`) are returned unchanged.
     */
    private fun resolveImage(raw: String?): String? {
        if (raw == null) return null
        if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("/")) return raw
        return "$imagesBase$raw"
    }
}
