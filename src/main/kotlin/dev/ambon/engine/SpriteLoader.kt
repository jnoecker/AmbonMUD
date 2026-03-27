package dev.ambon.engine

import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.sprite.SpriteCategory
import dev.ambon.domain.sprite.SpriteDefinition
import dev.ambon.domain.sprite.SpriteUnlockCondition
import dev.ambon.domain.sprite.SpriteVariant
import dev.ambon.persistence.yamlMapper

// ----- YAML DTOs -----

internal data class SpritesFile(
    val sprites: Map<String, SpriteEntryFile> = emptyMap(),
)

internal data class SpriteEntryFile(
    val displayName: String = "",
    val category: String = "achievement",
    val sortOrder: Int = 0,
    val unlock: SpriteUnlockFile = SpriteUnlockFile(),
    val variants: List<SpriteVariantFile> = emptyList(),
)

internal data class SpriteUnlockFile(
    val type: String = "",
    val minLevel: Int = 1,
    val achievementId: String = "",
)

internal data class SpriteVariantFile(
    val imageId: String = "",
    val displayName: String = "",
    val race: String? = null,
    val playerClass: String? = null,
    val gender: String? = null,
    val imagePath: String = "",
)

object SpriteLoader {
    /**
     * Loads custom sprite definitions from a classpath YAML resource.
     * Silently skips if the resource does not exist.
     */
    fun loadFromResource(
        resourcePath: String,
        registry: SpriteRegistry,
    ) {
        val stream = SpriteLoader::class.java.classLoader.getResourceAsStream(resourcePath) ?: return
        val file = yamlMapper.readValue<SpritesFile>(stream)

        for ((rawId, entry) in file.sprites) {
            val id = rawId.trim()
            require(id.isNotEmpty()) { "Sprite id cannot be blank" }
            require(entry.displayName.isNotBlank()) { "Sprite '$id' displayName cannot be blank" }
            require(entry.variants.isNotEmpty()) { "Sprite '$id' must have at least one variant" }

            val category = when (entry.category.lowercase()) {
                "tier" -> SpriteCategory.TIER
                "achievement" -> SpriteCategory.ACHIEVEMENT
                "staff" -> SpriteCategory.STAFF
                else -> error("Sprite '$id' has unknown category '${entry.category}'")
            }

            val unlockCondition = when (entry.unlock.type.lowercase()) {
                "level" -> SpriteUnlockCondition.Level(entry.unlock.minLevel)
                "achievement" -> {
                    require(entry.unlock.achievementId.isNotBlank()) {
                        "Sprite '$id' achievement unlock must specify achievementId"
                    }
                    SpriteUnlockCondition.Achievement(entry.unlock.achievementId)
                }
                "staff" -> SpriteUnlockCondition.Staff
                else -> error("Sprite '$id' has unknown unlock type '${entry.unlock.type}'")
            }

            val variants = entry.variants.mapIndexed { i, vf ->
                require(vf.imageId.isNotBlank()) { "Sprite '$id' variant #${i + 1} imageId cannot be blank" }
                require(vf.imagePath.isNotBlank()) { "Sprite '$id' variant #${i + 1} imagePath cannot be blank" }
                SpriteVariant(
                    imageId = vf.imageId,
                    displayName = vf.displayName.ifBlank { entry.displayName },
                    race = vf.race?.uppercase(),
                    playerClass = vf.playerClass?.uppercase(),
                    gender = vf.gender?.lowercase(),
                    imagePath = vf.imagePath,
                )
            }

            registry.register(
                SpriteDefinition(
                    id = id,
                    displayName = entry.displayName,
                    category = category,
                    unlockCondition = unlockCondition,
                    sortOrder = entry.sortOrder,
                    variants = variants,
                ),
            )
        }
    }

    /**
     * Auto-generates tier sprite definitions from the configured level tiers,
     * race IDs, and class IDs. Image paths follow the existing naming convention:
     * `player_sprites/{race}_{class}_{tierSuffix}.png`.
     */
    fun generateTierSprites(
        registry: SpriteRegistry,
        tierNames: Map<Int, String>,
        raceIds: List<String>,
        classIds: List<String>,
    ) {
        // Sort tiers ascending so sortOrder increases with level
        for ((level, name) in tierNames.toSortedMap()) {
            val tierSuffix = "t$level"
            val variants = mutableListOf<SpriteVariant>()

            for (race in raceIds) {
                for (cls in classIds) {
                    val rLow = race.lowercase()
                    val cLow = cls.lowercase()
                    variants.add(
                        SpriteVariant(
                            imageId = "${rLow}_${cLow}_$tierSuffix",
                            displayName = "$name (${race.lowercase().replaceFirstChar {
                                it.uppercase()
                            }} ${cls.lowercase().replaceFirstChar { it.uppercase() }})",
                            race = race.uppercase(),
                            playerClass = cls.uppercase(),
                            imagePath = "player_sprites/${rLow}_${cLow}_$tierSuffix.png",
                        ),
                    )
                }
            }

            registry.register(
                SpriteDefinition(
                    id = "tier_${name.lowercase().replace(' ', '_')}",
                    displayName = name,
                    category = SpriteCategory.TIER,
                    unlockCondition = SpriteUnlockCondition.Level(level),
                    sortOrder = level,
                    variants = variants,
                ),
            )
        }
    }

    /**
     * Auto-generates staff sprite definitions. Image paths follow the existing
     * convention: `player_sprites/{race}_base_tstaff.png`.
     */
    fun generateStaffSprites(
        registry: SpriteRegistry,
        raceIds: List<String>,
    ) {
        val variants = raceIds.map { race ->
            val rLow = race.lowercase()
            SpriteVariant(
                imageId = "${rLow}_base_tstaff",
                displayName = "Staff (${race.lowercase().replaceFirstChar { it.uppercase() }})",
                race = race.uppercase(),
                imagePath = "player_sprites/${rLow}_base_tstaff.png",
            )
        }

        registry.register(
            SpriteDefinition(
                id = "staff",
                displayName = "Staff",
                category = SpriteCategory.STAFF,
                unlockCondition = SpriteUnlockCondition.Staff,
                sortOrder = 1000,
                variants = variants,
            ),
        )
    }
}
