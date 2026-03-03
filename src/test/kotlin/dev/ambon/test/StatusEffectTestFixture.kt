package dev.ambon.test

import dev.ambon.bus.LocalOutboundBus
import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.RoomId
import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.MobRegistry
import dev.ambon.engine.PlayerRegistry
import dev.ambon.engine.items.ItemRegistry
import dev.ambon.engine.status.StatusEffectRegistry
import dev.ambon.engine.status.StatusEffectSystem
import dev.ambon.persistence.InMemoryPlayerRepository
import java.util.Random

class StatusEffectTestFixture(
    override val roomId: RoomId = RoomId("zone:room"),
    val clock: MutableClock = MutableClock(0L),
    val rng: Random = Random(42),
    val items: ItemRegistry = ItemRegistry(),
    val repo: InMemoryPlayerRepository = InMemoryPlayerRepository(),
    override val players: PlayerRegistry = buildTestPlayerRegistry(roomId, repo, items, clock = clock),
    override val mobs: MobRegistry = MobRegistry(),
    val outbound: LocalOutboundBus = LocalOutboundBus(),
) : TestFixtureBase {
    val registry = StatusEffectRegistry()
    val vitalsDirty = mutableListOf<SessionId>()
    val mobHpDirty = mutableListOf<MobId>()
    val statusDirty = mutableListOf<SessionId>()
    private val dirtyNotifier =
        object : dev.ambon.engine.DirtyNotifier {
            override fun playerVitalsDirty(sessionId: SessionId) {
                vitalsDirty.add(sessionId)
            }

            override fun playerStatusDirty(sessionId: SessionId) {
                statusDirty.add(sessionId)
            }

            override fun mobHpDirty(mobId: MobId) {
                mobHpDirty.add(mobId)
            }
        }

    val system: StatusEffectSystem =
        StatusEffectSystem(
            registry = registry,
            players = players,
            mobs = mobs,
            outbound = outbound,
            clock = clock,
            rng = rng,
            dirtyNotifier = dirtyNotifier,
        )
}
