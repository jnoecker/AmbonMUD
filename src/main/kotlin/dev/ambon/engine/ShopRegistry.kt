package dev.ambon.engine

import dev.ambon.domain.ids.ItemId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.items.Item
import dev.ambon.domain.world.ShopDefinition
import dev.ambon.engine.items.ItemRegistry

class ShopRegistry(
    private val itemRegistry: ItemRegistry,
) {
    private val shopsByRoom = mutableMapOf<RoomId, ShopDefinition>()

    fun register(shops: List<ShopDefinition>) {
        for (shop in shops) {
            shopsByRoom[shop.roomId] = shop
        }
    }

    fun shopInRoom(roomId: RoomId): ShopDefinition? = shopsByRoom[roomId]

    fun shopItems(shop: ShopDefinition): List<Pair<ItemId, Item>> =
        shop.itemIds.mapNotNull { itemId ->
            val template = itemRegistry.getTemplate(itemId) ?: return@mapNotNull null
            itemId to template
        }

    fun clear() {
        shopsByRoom.clear()
    }
}
