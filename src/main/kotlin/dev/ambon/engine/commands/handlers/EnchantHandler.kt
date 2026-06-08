package dev.ambon.engine.commands.handlers

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.crafting.EnchantResult
import dev.ambon.engine.crafting.EnchantSystem
import dev.ambon.engine.events.OutboundEvent

class EnchantHandler(
    ctx: EngineContext,
    private val enchantSystem: EnchantSystem,
) : CommandHandler {
    private val players = ctx.players
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter
    private val items = ctx.items
    private val metrics = ctx.metrics

    override fun register(router: CommandRouter) {
        router.on<Command.Enchant> { sid, cmd -> handleEnchant(sid, cmd) }
        router.on<Command.Enchantments> { sid, _ -> handleEnchantments(sid) }
    }

    private suspend fun handleEnchant(sessionId: SessionId, cmd: Command.Enchant) {
        val player = players.get(sessionId) ?: return

        when (val result = enchantSystem.enchant(sessionId, cmd.itemKeyword, cmd.enchantmentId, player.craftingSkills)) {
            is EnchantResult.Success -> {
                metrics.onGameEvent("crafting", "enchant")
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "You enchant ${result.enchantedItem.item.displayName} with ${result.enchantmentName}!",
                    ),
                )
                if (result.leveledUp) {
                    outbound.send(
                        OutboundEvent.SendInfo(
                            sessionId,
                            "[Enchanting] Your ${result.skill} skill increased to ${result.newLevel}!",
                        ),
                    )
                }
                syncItemsGmcp(sessionId, items, gmcpEmitter)
                gmcpEmitter?.sendCraftingResult(
                    sessionId,
                    type = "enchant",
                    skill = result.skill,
                    xpAwarded = result.xpAwarded,
                    leveledUp = result.leveledUp,
                    newLevel = result.newLevel,
                    itemName = result.enchantedItem.item.displayName,
                )
            }
            is EnchantResult.ItemNotFound ->
                outbound.send(OutboundEvent.SendError(sessionId, "You don't have that item."))
            is EnchantResult.NotEquippable ->
                outbound.send(OutboundEvent.SendError(sessionId, "You can only enchant equipment."))
            is EnchantResult.AlreadyEnchanted ->
                outbound.send(OutboundEvent.SendError(sessionId, "That item already has the maximum number of enchantments."))
            is EnchantResult.EnchantmentNotFound ->
                outbound.send(OutboundEvent.SendError(sessionId, "Unknown enchantment. Use 'enchantments' to list available."))
            is EnchantResult.NoEnchantmentAvailable ->
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "No enchantments available for that item with your current skill and materials.",
                    ),
                )
            is EnchantResult.AlreadyHasEnchantment ->
                outbound.send(OutboundEvent.SendError(sessionId, "That item already has ${result.name}."))
            is EnchantResult.WrongSlot ->
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "${result.enchantName} can only be applied to: ${result.validSlots.joinToString(", ")}.",
                    ),
                )
            is EnchantResult.SkillTooLow ->
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You need ${result.skill} level ${result.required} (you have ${result.current}).",
                    ),
                )
            is EnchantResult.MissingMaterials ->
                outbound.send(
                    OutboundEvent.SendError(
                        sessionId,
                        "You need: ${result.missing.joinToString(", ")}.",
                    ),
                )
        }
    }

    private suspend fun handleEnchantments(sessionId: SessionId) {
        val player = players.get(sessionId) ?: return
        val definitions = enchantSystem.allDefinitions()
        if (definitions.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "No enchantments are defined."))
            return
        }

        outbound.send(OutboundEvent.SendInfo(sessionId, "[ Available Enchantments ]"))
        for ((id, def) in definitions) {
            val skillLevel = player.craftingSkills[def.skill]?.level ?: 0
            val meetsSkill = skillLevel >= def.skillRequired
            val skillTag = if (meetsSkill) "" else " [skill too low]"
            val slots = if (def.targetSlots.isEmpty()) "any" else def.targetSlots.joinToString("/")
            val suffix = EnchantSystem.formatEnchantmentSuffix(def)
            outbound.send(
                OutboundEvent.SendInfo(
                    sessionId,
                    "  $id: ${def.displayName} $suffix — ${def.skill} ${def.skillRequired}, slots: $slots$skillTag",
                ),
            )
            val mats = def.materials.joinToString(", ") { "${it.quantity}x ${it.itemId}" }
            outbound.send(OutboundEvent.SendInfo(sessionId, "    Materials: $mats"))
        }
    }
}
