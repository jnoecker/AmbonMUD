package dev.ambon.engine.commands.handlers

import dev.ambon.config.EconomyConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.ShopRegistry
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import kotlin.math.roundToInt

class ShopHandler(
    router: CommandRouter,
    ctx: EngineContext,
    private val shopRegistry: ShopRegistry? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
    private val economyConfig: EconomyConfig = EconomyConfig(),
) {
    private val players = ctx.players
    private val items = ctx.items
    private val outbound = ctx.outbound
    private val gmcpEmitter = ctx.gmcpEmitter

    init {
        router.on<Command.ShopList> { sid, _ -> handleShopList(sid) }
        router.on<Command.Buy> { sid, cmd -> handleBuy(sid, cmd) }
        router.on<Command.Sell> { sid, cmd -> handleSell(sid, cmd) }
    }

    private suspend fun handleShopList(sessionId: SessionId) {
        players.withPlayer(sessionId) { me ->
            val shop = shopRegistry?.shopInRoom(me.roomId)
            if (shop == null) {
                outbound.send(OutboundEvent.SendText(sessionId, "There is no shop here."))
                return
            }
            val shopItems = shopRegistry.shopItems(shop)
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
        }
    }

    private suspend fun handleBuy(
        sessionId: SessionId,
        cmd: Command.Buy,
    ) {
        players.withPlayer(sessionId) { me ->
            val shop = shopRegistry?.shopInRoom(me.roomId)
            if (shop == null) {
                outbound.send(OutboundEvent.SendText(sessionId, "There is no shop here."))
                return
            }
            val keyword = cmd.keyword.lowercase()
            val shopItems = shopRegistry.shopItems(shop)
            val match =
                shopItems.firstOrNull { (_, item) ->
                    item.keyword.lowercase() == keyword ||
                        item.displayName.lowercase().contains(keyword)
                }
            if (match == null) {
                outbound.send(OutboundEvent.SendText(sessionId, "The shop doesn't sell '$keyword'."))
                return
            }
            val (itemId, item) = match
            val buyPrice = (item.basePrice * economyConfig.buyMultiplier).roundToInt().toLong()
            if (me.gold < buyPrice) {
                outbound.send(OutboundEvent.SendText(sessionId, "You can't afford ${item.displayName} ($buyPrice gold)."))
                return
            }
            val newItem = items.createFromTemplate(itemId)
            if (newItem == null) {
                outbound.send(OutboundEvent.SendText(sessionId, "That item is out of stock."))
                return
            }
            me.gold -= buyPrice
            items.addToInventory(sessionId, newItem)
            markVitalsDirty(sessionId)
            outbound.send(OutboundEvent.SendText(sessionId, "You buy ${item.displayName} for $buyPrice gold."))
            gmcpEmitter?.sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
        }
    }

    private suspend fun handleSell(
        sessionId: SessionId,
        cmd: Command.Sell,
    ) {
        players.withPlayer(sessionId) { me ->
            val shop = shopRegistry?.shopInRoom(me.roomId)
            if (shop == null) {
                outbound.send(OutboundEvent.SendText(sessionId, "There is no shop here."))
                return
            }
            val keyword = cmd.keyword
            val inv = items.inventory(sessionId)
            val lowerKeyword = keyword.lowercase()
            val invItem =
                inv.firstOrNull { instance ->
                    val nameMatch = instance.item.displayName.contains(lowerKeyword, ignoreCase = true)
                    instance.item.keyword.equals(keyword, ignoreCase = true) || nameMatch
                }
            if (invItem == null) {
                outbound.send(OutboundEvent.SendText(sessionId, "You don't have '$keyword'."))
                return
            }
            val sellPrice = (invItem.item.basePrice * economyConfig.sellMultiplier).roundToInt().toLong()
            if (sellPrice <= 0L) {
                outbound.send(OutboundEvent.SendText(sessionId, "${invItem.item.displayName} is worthless."))
                return
            }
            val removed = items.removeFromInventory(sessionId, invItem.item.keyword)
            if (removed == null) {
                outbound.send(OutboundEvent.SendText(sessionId, "You don't have '$keyword'."))
                return
            }
            me.gold += sellPrice
            markVitalsDirty(sessionId)
            outbound.send(OutboundEvent.SendText(sessionId, "You sell ${removed.item.displayName} for $sellPrice gold."))
            gmcpEmitter?.sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
        }
    }
}
