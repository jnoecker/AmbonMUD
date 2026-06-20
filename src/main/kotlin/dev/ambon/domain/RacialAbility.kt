package dev.ambon.domain

/**
 * When a racial passive ability fires.
 *
 * - [LOW_HEALTH] effects fire reactively after the player survives a hit and their HP has dropped
 *   to or below [RacialAbility.triggerHealthPct] of max. They turn a losing fight around.
 * - [LETHAL_BLOW] effects intercept an incoming attack that *would* reduce the player to 0 HP and
 *   transform or negate it, so the player cheats death once per cooldown.
 */
enum class RacialTrigger {
    LOW_HEALTH,
    LETHAL_BLOW,
}

/**
 * The distinct race-specific passive abilities. Each kind maps to one bespoke mechanic resolved by
 * `RacialAbilitySystem`; the [trigger] determines which combat hook invokes it. Config supplies the
 * tunable numbers (thresholds, cooldowns, multipliers) — the kind only selects the behaviour.
 */
enum class RacialAbilityKind(val trigger: RacialTrigger) {
    /** Pyrae: explode in flames, dealing area damage to every enemy in combat with the player. */
    PYRAE_IMMOLATE(RacialTrigger.LOW_HEALTH),

    /** Lustriae: slip through time for an extra attack; survive if that attack kills the attacker. */
    LUSTRIAE_TIMESLIP(RacialTrigger.LETHAL_BLOW),

    /** Aurelia: a dazzling bioluminescent burst that stuns nearby enemies for a few rounds. */
    AURELIA_DAZZLE(RacialTrigger.LOW_HEALTH),

    /** Lithae: petrify into stone form — disengage, become untargetable, regenerate, can't move. */
    LITHAE_STONEFORM(RacialTrigger.LETHAL_BLOW),

    /** Mycorae: release spores that spawn a few short-lived taunting mushroom pets. */
    MYCORAE_SPORES(RacialTrigger.LOW_HEALTH),

    /** Kitsarae: nullify a killing blow and convert it into a heal of equal magnitude. */
    KITSARAE_REVERSAL(RacialTrigger.LETHAL_BLOW),

    /** Archae: call the Drengariae — a short-lived combat pet that fights alongside the player. */
    ARCHAE_DRENGARIAE(RacialTrigger.LOW_HEALTH),

    /** Ophirae: draconic fury — outgoing damage is multiplied for a short duration. */
    OPHIRAE_WRATH(RacialTrigger.LOW_HEALTH),

    /** Aetherae: phase out of existence, ignoring the killing blow and the next round's damage. */
    AETHERAE_PHASE(RacialTrigger.LETHAL_BLOW),
}

/**
 * A resolved racial passive ability attached to a [RaceDef]. All numeric knobs are config-driven so
 * the same nine mechanics can be retuned per deployment without code changes. Fields not relevant to
 * a given [kind] keep their harmless defaults.
 */
data class RacialAbility(
    val kind: RacialAbilityKind,
    val displayName: String,
    /** Cooldown before the ability can fire again. Persisted on the player so a relog can't reset it. */
    val cooldownMs: Long,
    /** LOW_HEALTH only: fires once the player's HP is at or below this percent (1..100) of max. */
    val triggerHealthPct: Int = 0,
    /** Pyrae: AoE damage dealt to each enemy, as a fraction of the player's max HP. */
    val aoeDamagePctOfMaxHp: Double = 0.0,
    /** Ophirae: outgoing-damage multiplier while the wrath buff is active. */
    val damageMultiplier: Double = 1.0,
    /** Ophirae: how long the wrath buff lasts. */
    val buffDurationMs: Long = 0L,
    /** Aurelia: status-effect id (of effectType "stun") applied to enemies. Duration lives on the effect. */
    val stunStatusId: String? = null,
    /** Mycorae/Archae: pet template key to summon. */
    val petTemplateKey: String? = null,
    /** Mycorae: inclusive lower bound on the number of pets spawned. */
    val petCountMin: Int = 1,
    /** Mycorae: inclusive upper bound on the number of pets spawned. */
    val petCountMax: Int = 1,
    /** Mycorae/Archae: how long the summoned pets live before despawning. */
    val petDurationMs: Long = 0L,
    /** Lithae: HP restored on entering stone form, as a fraction of max HP. */
    val regenPctOfMaxHp: Double = 0.0,
    /** Lithae: status-effect id (of effectType "root") applied to self so the player can't move. */
    val stoneStatusId: String? = null,
    /** Lithae: how long the player stays untargetable in stone form. */
    val stoneDurationMs: Long = 0L,
    /** Aetherae: number of combat rounds the player stays phased/untargetable after the blow. */
    val phaseTicks: Int = 2,
    /** Message shown to the triggering player. */
    val selfMessage: String = "",
    /** Message broadcast to others in the room ({player} is substituted). */
    val roomMessage: String = "",
) {
    val trigger: RacialTrigger get() = kind.trigger
}
