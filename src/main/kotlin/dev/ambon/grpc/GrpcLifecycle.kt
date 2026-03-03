package dev.ambon.grpc

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private val log = KotlinLogging.logger {}

/**
 * Cancel a [Job] with a bounded timeout, logging a warning and forcing cancellation if the
 * graceful path takes too long.
 *
 * Used by both [GrpcOutboundDispatcher] and `GrpcOutboundBus` to stop their background
 * coroutines with consistent semantics.
 */
fun cancelJobWithTimeout(
    job: Job?,
    timeoutMs: Long,
    componentName: String,
) {
    runBlocking {
        try {
            withTimeout(timeoutMs) { job?.cancelAndJoin() }
        } catch (_: TimeoutCancellationException) {
            log.warn { "$componentName did not stop within ${timeoutMs}ms; forcing cancel" }
            job?.cancel()
        }
    }
}
