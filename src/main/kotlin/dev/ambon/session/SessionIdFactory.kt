package dev.ambon.session

import dev.ambon.domain.ids.SessionId

interface SessionIdFactory {
    fun allocate(): SessionId
}
