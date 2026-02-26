package dev.ambon.engine.commands.handlers

import dev.ambon.bus.OutboundBus
import dev.ambon.config.EconomyConfig
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.ShopRegistry
import dev.ambon.engine.commands.Command
import dev.ambon.engine.commands.CommandRouter
import dev.ambon.engine.commands.on
import dev.ambon.engine.events.OutboundEvent
import dev.ambon.engine.items.ItemRegistry
import kotlin.math.roundToInt

class ShopHandler(
    router: CommandRouter,
    private val players: PlayerRegistry,
    private val items: ItemRegistry,
    private val outbound: OutboundBus,
    private val shopRegistry: ShopRegistry? = null,
    private val gmcpEmitter: GmcpEmitter? = null,
    private val markVitalsDirty: (SessionId) -> Unit = {},
    private val economyConfig: EconomyConfig = EconomyConfig(),
) {
    init {
        router.on<Command.ShopList> { sid, _ -> handleShopList(sid) }
        router.on<Command.Buy> { sid, cmd -> handleBuy(sid, cmd) }
        router.on<Command.Sell> { sid, cmd -> handleSell(sid, cmd) }
    }

    private suspend fun handleShopList(sessionId: SessionId) {
        val me = players.get(sessionId) ?: return
        val shop = shopRegistry?.shopInRoom(me.roomId)
        if (shop == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "There is no shop here."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val shopItems = shopRegistry.shopItems(shop)
        if (shopItems.isEmpty()) {
            outbound.send(OutboundEvent.SendInfo(sessionId, "${shop.name} has nothing for sale."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
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
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleBuy(
        sessionId: SessionId,
        cmd: Command.Buy,
    ) {
        val me = players.get(sessionId) ?: return
        val shop = shopRegistry?.shopInRoom(me.roomId)
        if (shop == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "There is no shop here."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
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
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val (itemId, item) = match
        val buyPrice = (item.basePrice * economyConfig.buyMultiplier).roundToInt().toLong()
        if (me.gold < buyPrice) {
            outbound.send(OutboundEvent.SendText(sessionId, "You can't afford ${item.displayName} ($buyPrice gold)."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val newItem = items.createFromTemplate(itemId)
        if (newItem == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "That item is out of stock."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        me.gold -= buyPrice
        items.addToInventory(sessionId, newItem)
        markVitalsDirty(sessionId)
        outbound.send(OutboundEvent.SendText(sessionId, "You buy ${item.displayName} for $buyPrice gold."))
        gmcpEmitter?.sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }

    private suspend fun handleSell(
        sessionId: SessionId,
        cmd: Command.Sell,
    ) {
        val me = players.get(sessionId) ?: return
        val shop = shopRegistry?.shopInRoom(me.roomId)
        if (shop == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "There is no shop here."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
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
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val sellPrice = (invItem.item.basePrice * economyConfig.sellMultiplier).roundToInt().toLong()
        if (sellPrice <= 0L) {
            outbound.send(OutboundEvent.SendText(sessionId, "${invItem.item.displayName} is worthless."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        val removed = items.removeFromInventory(sessionId, invItem.item.keyword)
        if (removed == null) {
            outbound.send(OutboundEvent.SendText(sessionId, "You don't have '$keyword'."))
            outbound.send(OutboundEvent.SendPrompt(sessionId))
            return
        }
        me.gold += sellPrice
        markVitalsDirty(sessionId)
        outbound.send(OutboundEvent.SendText(sessionId, "You sell ${removed.item.displayName} for $sellPrice gold."))
        gmcpEmitter?.sendCharItemsList(sessionId, items.inventory(sessionId), items.equipment(sessionId))
        outbound.send(OutboundEvent.SendPrompt(sessionId))
    }
}
