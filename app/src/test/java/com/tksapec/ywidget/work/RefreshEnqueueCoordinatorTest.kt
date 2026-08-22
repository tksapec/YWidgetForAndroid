package com.tksapec.ywidget.work

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshEnqueueCoordinatorTest {
    @Test
    fun enqueueTransactionsCannotOvertakeEachOther() = runBlocking {
        val coordinator = RefreshEnqueueCoordinator()
        val events = mutableListOf<String>()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = launch {
            coordinator.runSerialized {
                events += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                events += "first-end"
            }
        }
        firstEntered.await()
        val second = launch {
            coordinator.runSerialized {
                events += "second"
            }
        }
        yield()

        assertEquals(listOf("first-start"), events)
        releaseFirst.complete(Unit)
        joinAll(first, second)
        assertEquals(listOf("first-start", "first-end", "second"), events)
    }
}
