package io.github.nolte.kamerplanter.core.network

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] that lets a cancellation through.
 *
 * Every client in this module wraps its calls so a failure becomes an outcome the screen can
 * name. A [CancellationException] is not one of those: it is the caller's scope ending, and
 * turning it into "the instance did not answer" would keep a dead screen's work alive and
 * report a failure nobody caused. One copy here rather than one per client.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        Result.failure(failure)
    }
