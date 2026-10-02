package dev.taladb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Keep ownership even when cancellation discards a dispatcher result. */
internal suspend fun <T : Any> acquireResource(
    dispatcher: CoroutineDispatcher,
    acquire: suspend () -> T,
    release: suspend (T) -> Unit,
): T {
    var acquired: T? = null
    try {
        return withContext(dispatcher) { acquire().also { acquired = it } }
    } catch (error: Throwable) {
        acquired?.let { resource ->
            try {
                withContext(NonCancellable + dispatcher) { release(resource) }
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
        }
        throw error
    }
}
