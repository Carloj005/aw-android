package com.carloj005.awanime.util

import kotlinx.coroutines.CancellationException

/**
 * Coroutine-safe equivalent of [runCatching] that always rethrows [CancellationException]
 * so structured concurrency is never broken by swallowed cancellation signals.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
