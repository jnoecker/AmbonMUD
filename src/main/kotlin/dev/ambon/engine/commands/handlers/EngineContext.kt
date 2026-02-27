package dev.ambon.engine.commands.handlers

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.world.World
import dev.ambon.engine.CombatSystem
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.WorldStateRegistry
import dev.ambon.engine.items.ItemRegistry

/**
 * Shared registries and services passed to every command handler.
 *
 * Grouping these into one parameter avoids threading 6–8 identical
 * constructor parameters through every handler class and through the
 * wiring code that instantiates them. Adding a new commonly-needed
 * dependency requires only updating this class and the one place that
 * constructs it — not every handler constructor individually.
 */
data class EngineContext(
    val players: PlayerRegistry,
    val mobs: MobRegistry,
    val world: World,
    val items: ItemRegistry,
    val outbound: OutboundBus,
    val combat: CombatSystem,
    val gmcpEmitter: GmcpEmitter?,
    val worldState: WorldStateRegistry?,
)
