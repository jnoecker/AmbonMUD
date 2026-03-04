package dev.ambon.engine

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId

interface DirtyNotifier {
    fun playerVitalsDirty(sessionId: SessionId)

    fun playerStatusDirty(sessionId: SessionId)

    fun mobHpDirty(mobId: MobId)

    fun playerCombatDirty(sessionId: SessionId)

    fun playerStatsDirty(sessionId: SessionId)

    companion object {
        val NO_OP: DirtyNotifier =
            object : DirtyNotifier {
                override fun playerVitalsDirty(sessionId: SessionId) = Unit

                override fun playerStatusDirty(sessionId: SessionId) = Unit

                override fun mobHpDirty(mobId: MobId) = Unit

                override fun playerCombatDirty(sessionId: SessionId) = Unit

                override fun playerStatsDirty(sessionId: SessionId) = Unit
            }
    }
}
