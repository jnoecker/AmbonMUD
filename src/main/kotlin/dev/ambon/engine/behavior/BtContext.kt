package dev.ambon.engine.behavior

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.mob.MobState
import dev.ambon.domain.world.World
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import java.time.Clock
import java.util.Random

class BtContext(
    val mob: MobState,
    val world: World,
    val mobs: MobRegistry,
    val players: PlayerRegistry,
    val outbound: OutboundBus,
    val clock: Clock,
    val rng: Random,
    val isMobInCombat: (MobId) -> Boolean,
    val startMobCombat: suspend (MobId, SessionId) -> Boolean,
    val fleeMob: suspend (MobId) -> Boolean,
    val gmcpEmitter: GmcpEmitter?,
    val mobMemory: MobBehaviorMemory,
)
