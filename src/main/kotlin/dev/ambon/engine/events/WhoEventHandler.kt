package dev.ambon.engine.events

import dev.ambon.domain.ids.SessionId
import dev.ambon.engine.PlayerRegistry
import dev.ambon.sharding.InterEngineBus
import dev.ambon.sharding.InterEngineMessage
import dev.ambon.sharding.PlayerSummary
import java.util.UUID

internal class WhoEventHandler(
    private val interEngineBus: InterEngineBus?,
    private val engineId: String,
    private val peerEngineCount: () -> Int,
    private val nowMillis: () -> Long,
    private val players: PlayerRegistry,
    private val sendInfo: suspend (SessionId, String) -> Unit,
    private val responseWaitMs: Long,
) {
    private val pendingWhoRequests = mutableMapOf<String, PendingWhoRequest>()

    suspend fun handleRemoteWho(sessionId: SessionId) {
        val bus = interEngineBus ?: return
        val requestId = UUID.randomUUID().toString()
        pendingWhoRequests[requestId] =
            PendingWhoRequest(
                sessionId = sessionId,
                deadlineEpochMs = nowMillis() + responseWaitMs,
                expectedPeerCount = peerEngineCount(),
            )
        bus.broadcast(
            InterEngineMessage.WhoRequest(
                requestId = requestId,
                replyToEngineId = engineId,
            ),
        )
    }

    suspend fun flushDueWhoResponses(nowEpochMs: Long = nowMillis()) {
        if (pendingWhoRequests.isEmpty()) return

        val itr = pendingWhoRequests.iterator()
        while (itr.hasNext()) {
            val (_, pending) = itr.next()
            if (nowEpochMs < pending.deadlineEpochMs) continue

            if (players.get(pending.sessionId) != null) {
                if (pending.remotePlayerNames.isNotEmpty()) {
                    val names = pending.remotePlayerNames.sorted().joinToString(", ")
                    sendInfo(pending.sessionId, "Also online: $names")
                }
                if (pending.expectedPeerCount > 0 && pending.respondedCount < pending.expectedPeerCount) {
                    sendInfo(
                        pending.sessionId,
                        "(Note: some game shards did not respond — results may be incomplete.)",
                    )
                }
            }
            itr.remove()
        }
    }

    fun removeForSession(sessionId: SessionId) {
        val itr = pendingWhoRequests.iterator()
        while (itr.hasNext()) {
            if (itr.next().value.sessionId == sessionId) {
                itr.remove()
            }
        }
    }

    fun onWhoResponse(response: InterEngineMessage.WhoResponse) {
        val pending = pendingWhoRequests[response.requestId] ?: return
        if (players.get(pending.sessionId) == null) {
            pendingWhoRequests.remove(response.requestId)
            return
        }
        pending.respondedCount++
        pending.remotePlayerNames += response.players.map(PlayerSummary::name)
    }
}

internal data class PendingWhoRequest(
    val sessionId: SessionId,
    val deadlineEpochMs: Long,
    val expectedPeerCount: Int,
    val remotePlayerNames: MutableSet<String> = linkedSetOf(),
    var respondedCount: Int = 0,
)
