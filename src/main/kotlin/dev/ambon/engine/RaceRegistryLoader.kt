package dev.ambon.engine

import dev.ambon.config.RaceEngineConfig
import dev.ambon.config.RacialAbilityConfig
import dev.ambon.domain.RaceDef
import dev.ambon.domain.RacialAbility
import dev.ambon.domain.RacialAbilityKind
import dev.ambon.domain.StatMap

object RaceRegistryLoader {
    fun load(
        config: RaceEngineConfig,
        registry: RaceRegistry,
    ) {
        for ((key, defConfig) in config.definitions) {
            registry.register(
                RaceDef(
                    id = key.uppercase(),
                    displayName = defConfig.displayName.ifEmpty { key },
                    description = defConfig.description,
                    backstory = defConfig.backstory,
                    traits = defConfig.traits,
                    abilities = defConfig.abilities,
                    image = defConfig.image,
                    statMods = StatMap(defConfig.statMods.mapKeys { (k, _) -> k.uppercase() }),
                    racialAbility = defConfig.racialAbility?.toDomain(),
                ),
            )
        }
    }

    private fun RacialAbilityConfig.toDomain(): RacialAbility =
        RacialAbility(
            kind = RacialAbilityKind.valueOf(kind.uppercase()),
            displayName = displayName,
            description = description,
            image = image,
            cooldownMs = cooldownMs,
            triggerHealthPct = triggerHealthPct,
            aoeDamagePctOfMaxHp = aoeDamagePctOfMaxHp,
            damageMultiplier = damageMultiplier,
            buffDurationMs = buffDurationMs,
            stunStatusId = stunStatusId,
            petTemplateKey = petTemplateKey,
            petCountMin = petCountMin,
            petCountMax = petCountMax,
            petDurationMs = petDurationMs,
            regenPctOfMaxHp = regenPctOfMaxHp,
            stoneStatusId = stoneStatusId,
            stoneDurationMs = stoneDurationMs,
            phaseTicks = phaseTicks,
            selfMessage = selfMessage,
            roomMessage = roomMessage,
        )
}
