package dev.ambon.engine

import dev.ambon.domain.ids.MobId
import dev.ambon.domain.ids.SessionId

interface DirtyNotifier {
    fun playerVitalsDirty(sessionId: SessionId)

    fun playerStatusDirty(sessionId: SessionId)

    fun mobHpDirty(mobId: MobId)

    companion object {
        val NO_OP: DirtyNotifier =
            object : DirtyNotifier {
                override fun playerVitalsDirty(sessionId: SessionId) = Unit

                override fun playerStatusDirty(sessionId: SessionId) = Unit

                override fun mobHpDirty(mobId: MobId) = Unit
            }
    }
}
