package dev.ambon.engine.behavior

import dev.ambon.bus.OutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId
import dev.ambon.domain.world.World
import dev.ambon.engine.GmcpEmitter
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.metrics.GameMetrics
import java.time.Clock
import java.util.Random

class BehaviorTreeSystem(
    private val world: World,
    private val mobs: MobRegistry,
    private val players: PlayerRegistry,
    private val outbound: OutboundBus,
    private val clock: Clock,
    private val rng: Random = Random(),
    private val isMobInCombat: (MobId) -> Boolean = { false },
    private val isMobRooted: (MobId) -> Boolean = { false },
    private val startMobCombat: suspend (MobId, SessionId) -> Boolean = { _, _ -> false },
    private val fleeMob: suspend (MobId) -> Boolean = { false },
    private val gmcpEmitter: GmcpEmitter? = null,
    private val minActionDelayMs: Long = 2_000L,
    private val maxActionDelayMs: Long = 5_000L,
    private val metrics: GameMetrics = GameMetrics.noop(),
) {
    private val memories = mutableMapOf<MobId, MobBehaviorMemory>()
    private val nextActAtMillis = mutableMapOf<MobId, Long>()

    fun hasBehaviorTree(mobId: MobId): Boolean {
        val mob = mobs.get(mobId) ?: return false
        return mob.behaviorTree != null
    }

    suspend fun tick(maxActionsPerTick: Int = 10): Int {
        val now = clock.millis()
        var actions = 0

        val mobList = mobs.all().filter { it.behaviorTree != null }.toMutableList()
        mobList.shuffle(rng)

        for (m in mobList) {
            if (actions >= maxActionsPerTick) break

            val tree = m.behaviorTree ?: continue

            val dueAt = nextActAtMillis[m.id]
            if (dueAt == null) {
                nextActAtMillis[m.id] = now + randomDelay()
                continue
            }

            if (now < dueAt) continue

            if (isMobRooted(m.id)) {
                nextActAtMillis[m.id] = now + randomDelay()
                continue
            }

            val memory = memories.getOrPut(m.id) { MobBehaviorMemory() }
            val ctx =
                BtContext(
                    mob = m,
                    world = world,
                    mobs = mobs,
                    players = players,
                    outbound = outbound,
                    clock = clock,
                    rng = rng,
                    isMobInCombat = isMobInCombat,
                    startMobCombat = startMobCombat,
                    fleeMob = fleeMob,
                    gmcpEmitter = gmcpEmitter,
                    mobMemory = memory,
                )

            tree.tick(ctx)

            nextActAtMillis[m.id] = now + randomDelay()
            actions++
        }
        return actions
    }

    fun onMobRemoved(mobId: MobId) {
        memories.remove(mobId)
        nextActAtMillis.remove(mobId)
    }

    fun onMobSpawned(mobId: MobId) {
        memories.remove(mobId)
        nextActAtMillis.remove(mobId)
    }

    private fun randomDelay(): Long {
        val range = (maxActionDelayMs - minActionDelayMs + 1).toInt()
        return if (range <= 0) minActionDelayMs else minActionDelayMs + rng.nextInt(range)
    }
}
