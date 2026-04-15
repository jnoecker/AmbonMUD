package dev.ambon.engine.commands.handlers

import dev.ambon.config.EconomyConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.ReputationRequirement
import dev.ambon.domain.world.ShopDefinition
import dev.ambon.engine.PlayerState
import dev.ambon.engine.ReputationSystem
import dev.ambon.engine.ShopRegistry
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandHandler
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.matchesKeyword
import kotlin.math.roundToInt

class ShopHandler(
    private val ctx: EngineContext,
    private val shopRegistry: ShopRegistry? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
    private val economyConfig: EconomyConfig = EconomyConfig(),
    private val reputationSystem: ReputationSystem? = null,
) : CommandHandler {
    private val players = ctx.players
    private val items = ctx.items
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    override fun register(router: CommandRouter) {
        router.on<Command.ShopList> { sid, _ -> handleShopList(sid) }
        router.on<Command.Buy> { sid, cmd -> handleBuy(sid, cmd) }
        router.on<Command.Sell> { sid, cmd -> handleSell(sid, cmd) }
    }

    /** Returns the shop at [me]'s current room, or null (sending an error) if there is none. */
    private suspend fun findShop(sessionId: SessionId, me: PlayerState, command: String? = null): ShopDefinition? {
        val shop = shopRegistry?.shopInRoom(me.roomId)
        if (shop == null) {
            outbound.send(OutboundEvent.SendError(sessionId, "There is no shop here."))
            gmcpEmitter?.sendUiFeedback(
                sessionId,
                "error",
                "There is no shop here.",
                code = "NO_SHOP",
                scope = "shop",
                command = command,
            )
        }
        return shop
    }

    /**
     * Enforces the shop's reputation gate. Returns true when the player may proceed.
     * On refusal, sends a tier-aware error message and GMCP feedback.
     */
    private suspend fun checkReputationGate(
        sessionId: SessionId,
        me: PlayerState,
        shop: ShopDefinition,
        command: String,
    ): Boolean {
        val req = shop.requiredReputation ?: return true
        val rep = reputationSystem ?: return true
        if (me.isStaff) return true
        val standing = rep.getStanding(me, req.faction)
        val factionName = rep.getFaction(req.faction)?.name ?: req.faction
        val tierLabel = rep.tierLabel(standing)
        return when {
            req.min != null && standing < req.min -> {
                refuse(sessionId, shop, req, factionName, tierLabel, command, code = "REPUTATION_TOO_LOW")
                false
            }
            req.max != null && standing > req.max -> {
                refuse(sessionId, shop, req, factionName, tierLabel, command, code = "REPUTATION_TOO_HIGH")
                false
            }
            else -> true
        }
    }

    private suspend fun refuse(
        sessionId: SessionId,
        shop: ShopDefinition,
        @Suppress("UNUSED_PARAMETER") req: ReputationRequirement,
        factionName: String,
        tierLabel: String,
        command: String,
        code: String,
    ) {
        val msg = "${shop.name} refuses to deal with you — $tierLabel standing with $factionName is not welcome here."
        outbound.send(OutboundEvent.SendError(sessionId, msg))
        gmcpEmitter?.sendUiFeedback(sessionId, "error", msg, code = code, scope = "shop", command = command)
    }

    private suspend fun handleShopList(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val shop = findShop(sessionId, me, command = "list") ?: return
            if (!checkReputationGate(sessionId, me, shop, command = "list")) return
            val shopItems = shopRegistry!!.shopItems(shop)
            if (shopItems.isEmpty()) {
                outbound.send(OutboundEvent.SendInfo(sessionId, "${shop.name} has nothing for sale."))
                return
            }
            outbound.send(OutboundEvent.SendInfo(sessionId, "[ ${shop.name} ]"))
            outbound.send(OutboundEvent.SendInfo(sessionId, "  %-30s %8s %8s".format("Item", "Buy", "Sell")))
            for ((_, item) in shopItems) {
                val buyPrice = (item.basePrice * economyConfig.buyMultiplier).roundToInt()
                val sellPrice = (item.basePrice * economyConfig.sellMultiplier).roundToInt()
                outbound.send(
                    OutboundEvent.SendInfo(
                        sessionId,
                        "  %-30s %5d gp %5d gp".format(item.displayName, buyPrice, sellPrice),
                    ),
                )
            }
            ctx.emitShopGmcp(sessionId)
        }
    }

    private suspend fun handleBuy(
        sessionId: SessionId,
        cmd: Command.Buy,
    ) {
        players.withPlayer(sessionId) { me ->
            val shop = findShop(sessionId, me, command = "buy") ?: return
            if (!checkReputationGate(sessionId, me, shop, command = "buy")) return
            val shopItems = shopRegistry!!.shopItems(shop)
            val match =
                shopItems.firstOrNull { (_, item) -> item.matchesKeyword(cmd.keyword) }
            if (match == null) {
                val msg = "The shop doesn't sell '${cmd.keyword}'."
                outbound.send(OutboundEvent.SendError(sessionId, msg))
                gmcpEmitter?.sendUiFeedback(sessionId, "error", msg, code = "ITEM_NOT_FOUND", scope = "shop", command = "buy")
                return
            }
            val (itemId, item) = match
            val buyPrice = (item.basePrice * economyConfig.buyMultiplier).roundToInt().toLong()
            if (!me.isStaff && me.gold < buyPrice) {
                val msg = "You can't afford ${item.displayName} ($buyPrice gold)."
                outbound.send(OutboundEvent.SendError(sessionId, msg))
                gmcpEmitter?.sendUiFeedback(sessionId, "error", msg, code = "INSUFFICIENT_GOLD", scope = "shop", command = "buy")
                return
            }
            val newItem = items.createFromTemplate(itemId)
            if (newItem == null) {
                val msg = "That item is out of stock."
                outbound.send(OutboundEvent.SendError(sessionId, msg))
                gmcpEmitter?.sendUiFeedback(sessionId, "error", msg, code = "OUT_OF_STOCK", scope = "shop", command = "buy")
                return
            }
            if (!me.isStaff) me.gold -= buyPrice
            items.addToInventory(sessionId, newItem)
            markVitalsDirty(sessionId)
            outbound.send(OutboundEvent.SendText(sessionId, "You buy ${item.displayName} for $buyPrice gold."))
            syncItemsGmcp(sessionId, items, gmcpEmitter)
            ctx.emitShopGmcp(sessionId)
        }
    }

    private suspend fun handleSell(
        sessionId: SessionId,
        cmd: Command.Sell,
    ) {
        players.withPlayer(sessionId) { me ->
            val shop = findShop(sessionId, me, command = "sell") ?: return
            if (!checkReputationGate(sessionId, me, shop, command = "sell")) return
            val keyword = cmd.keyword
            val inv = items.inventory(sessionId)
            val invItem = inv.firstOrNull { it.matchesKeyword(keyword) }
            if (invItem == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't have '$keyword'."))
                return
            }
            val sellPrice = (invItem.item.basePrice * economyConfig.sellMultiplier).roundToInt().toLong()
            if (sellPrice <= 0L) {
                outbound.send(OutboundEvent.SendError(sessionId, "${invItem.item.displayName} is worthless."))
                return
            }
            val removed = items.removeFromInventory(sessionId, invItem.item.keyword)
            if (removed == null) {
                outbound.send(OutboundEvent.SendError(sessionId, "You don't have '$keyword'."))
                return
            }
            me.gold += sellPrice
            markVitalsDirty(sessionId)
            outbound.send(OutboundEvent.SendText(sessionId, "You sell ${removed.item.displayName} for $sellPrice gold."))
            syncItemsGmcp(sessionId, items, gmcpEmitter)
            ctx.emitShopGmcp(sessionId)
        }
    }
}
