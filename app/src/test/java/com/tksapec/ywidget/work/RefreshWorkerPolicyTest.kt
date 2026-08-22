package com.tksapec.ywidget.work

import androidx.work.ExistingWorkPolicy
import com.tksapec.ywidget.data.NewsItem
import com.tksapec.ywidget.data.REFRESH_ACTIVE_TIMEOUT_MILLIS
import com.tksapec.ywidget.data.RefreshResult
import com.tksapec.ywidget.data.WidgetSettings
import com.tksapec.ywidget.network.EmptyRssException
import com.tksapec.ywidget.network.RssHttpException
import com.tksapec.ywidget.network.RssParseException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshWorkerPolicyTest {
    @Test
    fun refreshWorkStartSchedulesCleanupAfterRunningState() = runBlocking {
        val calls = mutableListOf<String>()

        val started = prepareRefreshWork(
            markRunning = { calls += "running"; true },
            enqueueCleanup = { calls += "cleanup" },
            shouldUpdateWidget = { true },
            updateWidget = { calls += "widget" },
        )

        assertTrue(started)
        assertEquals(listOf("running", "cleanup", "widget"), calls)
    }

    @Test
    fun refreshWorkStartStopsWhenGenerationOwnershipWasLost() = runBlocking {
        val calls = mutableListOf<String>()

        val started = prepareRefreshWork(
            markRunning = { calls += "running"; false },
            enqueueCleanup = { calls += "cleanup" },
            shouldUpdateWidget = { true },
            updateWidget = { calls += "widget" },
        )

        assertFalse(started)
        assertEquals(listOf("running"), calls)
    }

    @Test
    fun refreshWorkStartSkipsWidgetUpdateWhenExistingNewsCanRemainVisible() = runBlocking {
        val calls = mutableListOf<String>()

        val started = prepareRefreshWork(
            markRunning = { calls += "running"; true },
            enqueueCleanup = { calls += "cleanup" },
            shouldUpdateWidget = { false },
            updateWidget = { calls += "widget" },
        )

        assertTrue(started)
        assertEquals(listOf("running", "cleanup"), calls)
    }

    @Test
    fun refreshStartRedrawDecisionDependsOnExistingData() {
        assertEquals(true, shouldRedrawWhenRefreshStarts(WidgetSettings()))
        assertEquals(
            false,
            shouldRedrawWhenRefreshStarts(
                WidgetSettings(news = listOf(NewsItem("title", "url"))),
            ),
        )
        assertEquals(true, shouldRedrawWhenWeatherRefreshStarts(WidgetSettings()))
        assertEquals(
            false,
            shouldRedrawWhenWeatherRefreshStarts(
                WidgetSettings(weatherCode = 0, temperatureCelsius = 20.0),
            ),
        )
    }

    @Test
    fun finishRefreshVerifiesOwnedStateBeforeFinalRedraw() = runBlocking {
        val calls = mutableListOf<String>()
        val finishedState = WidgetSettings(
            lastRefreshFinishedAtMillis = 2_000L,
            lastRefreshResult = RefreshResult.Success,
        )

        val result = finishRefreshAndRedraw(
            finishRefresh = { calls += "finish"; true },
            readSettings = { calls += "read"; finishedState },
            redrawWidgets = { calls += "redraw"; true },
        )

        assertTrue(result)
        assertEquals(listOf("finish", "read", "redraw"), calls)
    }

    @Test
    fun finishRefreshDoesNotTouchNewGenerationWhenOwnershipWasLost() = runBlocking {
        val calls = mutableListOf<String>()

        val result = finishRefreshAndRedraw(
            finishRefresh = { calls += "finish"; false },
            readSettings = { calls += "read"; WidgetSettings() },
            redrawWidgets = { calls += "redraw"; true },
        )

        assertTrue(result)
        assertEquals(listOf("finish", "redraw"), calls)
    }

    @Test
    fun finishRefreshStillRedrawsWhenStateVerificationFails() = runBlocking {
        val calls = mutableListOf<String>()

        val result = finishRefreshAndRedraw(
            finishRefresh = { calls += "finish"; true },
            readSettings = { calls += "read"; error("read failed") },
            redrawWidgets = { calls += "redraw"; true },
        )

        assertTrue(result)
        assertEquals(listOf("finish", "read", "redraw"), calls)
    }

    @Test
    fun userRefreshAlwaysReplacesExistingWork() {
        assertEquals(ExistingWorkPolicy.REPLACE, RefreshWorker.userEnqueuePolicy())
    }

    @Test
    fun periodicRefreshKeepsActiveWork() {
        val settings = WidgetSettings(newsRefreshing = true, refreshStartedAtMillis = 1_000L)

        assertEquals(ExistingWorkPolicy.KEEP, RefreshWorker.periodicEnqueuePolicy(settings, 2_000L))
    }

    @Test
    fun periodicRefreshKeepsQueuedWorkEvenAfterRunningTimeoutWindow() {
        val settings = WidgetSettings(refreshQueued = true)

        assertEquals(
            ExistingWorkPolicy.KEEP,
            RefreshWorker.periodicEnqueuePolicy(settings, REFRESH_ACTIVE_TIMEOUT_MILLIS + 10_000L),
        )
    }

    @Test
    fun periodicRefreshReplacesStaleRunningWork() {
        val settings = WidgetSettings(newsRefreshing = true, refreshStartedAtMillis = 1_000L)

        assertEquals(
            ExistingWorkPolicy.REPLACE,
            RefreshWorker.periodicEnqueuePolicy(settings, 1_000L + REFRESH_ACTIVE_TIMEOUT_MILLIS),
        )
    }

    @Test
    fun transientFailureClassificationDoesNotRetryPermanentRssFailures() {
        assertFalse(isTransientFailure(RssHttpException(404)))
        assertFalse(isTransientFailure(EmptyRssException("Top")))
        assertFalse(isTransientFailure(RssParseException(IllegalStateException("bad xml"))))
    }

    @Test
    fun transientFailureClassificationRetriesTemporaryFailures() {
        assertTrue(isTransientFailure(RssHttpException(500)))
        assertTrue(isTransientFailure(RssHttpException(429)))
        assertTrue(isTransientFailure(SocketTimeoutException("timeout")))
        assertTrue(isTransientFailure(IllegalStateException("Weather request failed: HTTP 503")))
    }

    @Test
    fun periodicIntervalClampsTenMinutesToFifteen() {
        assertEquals(15L, RefreshWorker.periodicIntervalMinutes(10L))
    }

    @Test
    fun periodicIntervalKeepsSupportedSettings() {
        assertEquals(15L, RefreshWorker.periodicIntervalMinutes(15L))
        assertEquals(30L, RefreshWorker.periodicIntervalMinutes(30L))
        assertEquals(60L, RefreshWorker.periodicIntervalMinutes(60L))
    }
}
