package com.tksapec.ywidget.work

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RefreshEnqueueCoordinator {
    private val mutex = Mutex()

    suspend fun <T> runSerialized(block: suspend () -> T): T {
        return mutex.withLock { block() }
    }
}
