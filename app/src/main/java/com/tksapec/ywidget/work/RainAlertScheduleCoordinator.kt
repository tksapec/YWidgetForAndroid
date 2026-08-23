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
