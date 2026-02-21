package dev.ambon.session

import dev.ambon.domain.ids.SessionId
import java.util.concurrent.atomic.AtomicLong

class AtomicSessionIdFactory(startId: Long = 1L) : SessionIdFactory {
    private val seq = AtomicLong(startId)

    override fun allocate(): SessionId = SessionId(seq.getAndIncrement())
}
