package com.tksapec.ywidget.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class RainAlertScheduleCoordinatorTest {
    @Test
    fun cancellationDoesNotInterruptAnEnteredSchedulingTransaction() = runBlocking {
        val coordinator = RainAlertScheduleCoordinator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()

        val job = launch {
            coordinator.runSerialized {
                entered.complete(Unit)
                release.await()
                completed.complete(Unit)
            }
        }

        entered.await()
        job.cancel()
        release.complete(Unit)
        job.cancelAndJoin()

        assertTrue(completed.isCompleted)
    }

    @Test
    fun temporaryInvalidationRestoresEnablementWhenCancellationOperationFails() = runBlocking {
        var enabled = true
        var failureObserved = false

        try {
            withRainAlertGenerationInvalidated(
                wasEnabled = true,
                setEnabled = { enabled = it },
            ) {
                throw IllegalStateException("cancel failed")
            }
        } catch (_: IllegalStateException) {
            failureObserved = true
        }

        assertTrue(failureObserved)
        assertTrue(enabled)
    }
}
