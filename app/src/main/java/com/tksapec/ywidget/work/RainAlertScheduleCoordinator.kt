package com.tksapec.ywidget.work

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RainAlertScheduleCoordinator {
    private val mutex = Mutex()

    suspend fun <T> runSerialized(block: suspend () -> T): T {
        return mutex.withLock {
            withContext(NonCancellable) {
                block()
            }
        }
    }
}

internal suspend fun <T> withRainAlertGenerationInvalidated(
    wasEnabled: Boolean,
    setEnabled: suspend (Boolean) -> Unit,
    block: suspend () -> T,
): T {
    if (!wasEnabled) return block()
    setEnabled(false)
    return try {
        block()
    } finally {
        setEnabled(true)
    }
}
