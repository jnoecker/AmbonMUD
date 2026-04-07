package dev.ambon.engine

import com.fasterxml.jackson.module.kotlin.readValue
import dev.ambon.domain.sprite.SpriteCategory
import dev.ambon.domain.sprite.SpriteDefinition
import dev.ambon.domain.sprite.SpriteRequirement
import dev.ambon.domain.sprite.SpriteUnlockCondition
import dev.ambon.domain.sprite.SpriteVariant
import dev.ambon.persistence.yamlMapper
import java.io.File
import java.io.InputStream

// ----- YAML DTOs -----

internal data class SpritesFile(
    val sprites: Map<String, SpriteEntryFile> = emptyMap(),
)

internal data class SpriteEntryFile(
    val displayName: String = "",
    val description: String = "",
    val category: String = "achievement",
    val sortOrder: Int = 0,
    /** Legacy single-condition unlock. Ignored if [requirements] is non-empty. */
    val unlock: SpriteUnlockFile = SpriteUnlockFile(),
    /** New requirements list (AND logic). Takes precedence over [unlock]. */
    val requirements: List<SpriteRequirementFile> = emptyList(),
    /** Single image path (shorthand for sprites with no variants). */
    val image: String = "",
    val variants: List<SpriteVariantFile> = emptyList(),
)

internal data class SpriteUnlockFile(
    val type: String = "",
    val minLevel: Int = 1,
    val achievementId: String = "",
)

internal data class SpriteRequirementFile(
    val type: String = "",
    val level: Int = 1,
    val race: String = "",
    val playerClass: String = "",
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
     * Loads custom sprite definitions from a YAML resource.
     *
     * Checks $AMBONMUD_DATA_DIR/<resourcePath> on the filesystem first (for
     * container deployments that fetch sprites.yaml to a bind-mounted data
     * volume at runtime — see AppConfigLoader + WorldLoader for the matching
     * overlay lookups). Falls back to the classpath for sprites bundled in
     * the fat JAR. Silently no-ops if neither location has the resource.
     */
    fun loadFromResource(
        resourcePath: String,
        registry: SpriteRegistry,
    ) {
        val dataDirFile =
            System
                .getenv("AMBONMUD_DATA_DIR")
                ?.let { File(it, resourcePath) }
                ?.takeIf { it.isFile }
        val stream: InputStream =
            dataDirFile?.inputStream()
                ?: SpriteLoader::class.java.classLoader.getResourceAsStream(resourcePath)
                ?: return
        val file = stream.use { yamlMapper.readValue<SpritesFile>(it) }

        for ((rawId, entry) in file.sprites) {
            val id = rawId.trim()
            require(id.isNotEmpty()) { "Sprite id cannot be blank" }
            require(entry.displayName.isNotBlank()) { "Sprite '$id' displayName cannot be blank" }

            val category = when (entry.category.lowercase()) {
                "tier" -> SpriteCategory.TIER
                "achievement" -> SpriteCategory.ACHIEVEMENT
                "staff" -> SpriteCategory.STAFF
                "general" -> SpriteCategory.GENERAL
                else -> error("Sprite '$id' has unknown category '${entry.category}'")
            }

            // Parse requirements (new format) or legacy unlock condition
            val requirements = entry.requirements.map { rf -> parseRequirement(id, rf) }

            val unlockCondition = if (requirements.isNotEmpty()) {
                // Default legacy condition when using requirements (won't be checked)
                SpriteUnlockCondition.Level(1)
            } else {
                when (entry.unlock.type.lowercase()) {
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
            }

            // Build variants: support single-image shorthand or explicit variants list
            val variants = if (entry.variants.isNotEmpty()) {
                entry.variants.mapIndexed { i, vf ->
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
            } else if (entry.image.isNotBlank()) {
                // Single-image shorthand: imageId = sprite id, imagePath = image field
                listOf(
                    SpriteVariant(
                        imageId = id,
                        displayName = entry.displayName,
                        imagePath = entry.image,
                    ),
                )
            } else {
                error("Sprite '$id' must have at least one variant or an image path")
            }

            registry.register(
                SpriteDefinition(
                    id = id,
                    displayName = entry.displayName,
                    description = entry.description,
                    category = category,
                    unlockCondition = unlockCondition,
                    requirements = requirements,
                    sortOrder = entry.sortOrder,
                    variants = variants,
                ),
            )
        }
    }

    private fun parseRequirement(spriteId: String, rf: SpriteRequirementFile): SpriteRequirement =
        when (rf.type.lowercase()) {
            "minlevel", "level" -> SpriteRequirement.MinLevel(rf.level)
            "race" -> {
                require(rf.race.isNotBlank()) { "Sprite '$spriteId' race requirement must specify race" }
                SpriteRequirement.Race(rf.race.uppercase())
            }
            "class" -> {
                require(rf.playerClass.isNotBlank()) { "Sprite '$spriteId' class requirement must specify playerClass" }
                SpriteRequirement.PlayerClass(rf.playerClass.uppercase())
            }
            "achievement" -> {
                require(rf.achievementId.isNotBlank()) { "Sprite '$spriteId' achievement requirement must specify achievementId" }
                SpriteRequirement.Achievement(rf.achievementId)
            }
            "staff" -> SpriteRequirement.Staff
            else -> error("Sprite '$spriteId' has unknown requirement type '${rf.type}'")
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
