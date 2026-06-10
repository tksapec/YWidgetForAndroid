package com.tksapec.ywidget.work

import androidx.work.ExistingWorkPolicy
import com.tksapec.ywidget.data.REFRESH_ACTIVE_TIMEOUT_MILLIS
import com.tksapec.ywidget.data.WidgetSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshWorkerPolicyTest {
    @Test
    fun userRefreshAlwaysReplacesExistingWork() {
        assertEquals(ExistingWorkPolicy.REPLACE, RefreshWorker.userEnqueuePolicy())
    }

    @Test
    fun periodicRefreshKeepsActiveWork() {
        val settings = WidgetSettings(refreshQueued = true, refreshStartedAtMillis = 1_000L)

        assertEquals(ExistingWorkPolicy.KEEP, RefreshWorker.periodicEnqueuePolicy(settings, 2_000L))
    }

    @Test
    fun periodicRefreshReplacesStaleWork() {
        val settings = WidgetSettings(refreshQueued = true, refreshStartedAtMillis = 1_000L)

        assertEquals(
            ExistingWorkPolicy.REPLACE,
            RefreshWorker.periodicEnqueuePolicy(settings, 1_000L + REFRESH_ACTIVE_TIMEOUT_MILLIS),
        )
    }
}
