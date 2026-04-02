package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.sprite.SpriteCategory
import dev.ambon.engine.SpriteRegistry
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent

class SpriteHandler(
    ctx: EngineContext,
    private val spriteRegistry: SpriteRegistry?,
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.SpriteList> { sid, _ -> handleSpriteList(sid) }
        router.on<Command.SpriteSet> { sid, cmd -> handleSpriteSet(sid, cmd) }
        router.on<Command.SpriteDefault> { sid, _ -> handleSpriteDefault(sid) }
    }

    private suspend fun handleSpriteList(sessionId: SessionId) {
        val reg = spriteRegistry
        if (reg == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Sprites are not available."))
            return
        }
        val ps = players.get(sessionId) ?: return

        val available = reg.availableVariants(
            level = ps.level,
            unlockedAchievementIds = ps.unlockedAchievementIds,
            isStaff = ps.isStaff,
            playerRace = ps.race,
            playerClass = ps.playerClass,
            playerGender = ps.gender,
        )

        if (available.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "You have no sprites available."))
            return
        }

        val activeImageId = ps.activeSprite
            ?: reg.autoResolve(
                level = ps.level,
                isStaff = ps.isStaff,
                playerRace = ps.race,
                playerClass = ps.playerClass,
                playerGender = ps.gender,
            )?.imageId

        val grouped = available.groupBy { (def, _) -> def.category }
        val sb = StringBuilder()
        sb.appendLine("=== Your Sprites ===")
        if (ps.activeSprite == null) {
            sb.appendLine("Mode: auto (highest tier)")
        }
        sb.appendLine()

        for (cat in listOf(SpriteCategory.GENERAL, SpriteCategory.TIER, SpriteCategory.ACHIEVEMENT, SpriteCategory.STAFF)) {
            val entries = grouped[cat] ?: continue
            sb.appendLine("--- ${cat.name.lowercase().replaceFirstChar { it.uppercase() }} Sprites ---")
            // Sort by definition sortOrder, then variant displayName
            val sorted = entries.sortedWith(
                compareByDescending<Pair<dev.ambon.domain.sprite.SpriteDefinition, dev.ambon.domain.sprite.SpriteVariant>> {
                    it.first.sortOrder
                }.thenBy { it.second.displayName },
            )
            for ((_, variant) in sorted) {
                val marker = if (variant.imageId == activeImageId) " [*]" else "    "
                sb.appendLine("$marker ${variant.displayName}  (${variant.imageId})")
            }
            sb.appendLine()
        }
        sb.append("Use 'sprite set <id>' to select, or 'sprite default' for auto.")
        outbound.send(OutboundEvent.SendInfo(sessionId, sb.toString().trimEnd()))
    }

    private suspend fun handleSpriteSet(
        sessionId: SessionId,
        cmd: Command.SpriteSet,
    ) {
        val reg = spriteRegistry
        if (reg == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Sprites are not available."))
            return
        }
        val ps = players.get(sessionId) ?: return

        val variant = reg.validateSelection(
            imageId = cmd.imageId,
            level = ps.level,
            unlockedAchievementIds = ps.unlockedAchievementIds,
            isStaff = ps.isStaff,
            playerRace = ps.race,
            playerClass = ps.playerClass,
            playerGender = ps.gender,
        )

        if (variant == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "Unknown or unavailable sprite: ${cmd.imageId}"))
            return
        }

        ps.activeSprite = variant.imageId
        outbound.send(OutboundEvent.SendInfo(sessionId, "Sprite set to: ${variant.displayName}"))
        gmcpEmitter?.sendCharName(sessionId, ps)
        gmcpEmitter?.sendCharSprites(sessionId, ps)
    }

    private suspend fun handleSpriteDefault(sessionId: SessionId) {
        val ps = players.get(sessionId) ?: return
        ps.activeSprite = null
        outbound.send(OutboundEvent.SendInfo(sessionId, "Sprite set to auto (highest unlocked tier)."))
        gmcpEmitter?.sendCharName(sessionId, ps)
        gmcpEmitter?.sendCharSprites(sessionId, ps)
    }
}
