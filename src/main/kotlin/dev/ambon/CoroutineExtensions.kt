package dev.ambon

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = KotlinLogging.logger {}

/**
 * Launches a coroutine that repeatedly calls [block] every [intervalMs] milliseconds.
 * The first execution happens after the first delay.
 * Failures are logged as warnings under [name] and the loop continues.
 */
internal fun CoroutineScope.launchPeriodic(
    intervalMs: Long,
    name: String,
    block: suspend () -> Unit,
): Job =
    launch {
        while (isActive) {
            delay(intervalMs)
            runCatching { block() }.onFailure { err ->
                log.warn(err) { "$name failed" }
            }
        }
    }
