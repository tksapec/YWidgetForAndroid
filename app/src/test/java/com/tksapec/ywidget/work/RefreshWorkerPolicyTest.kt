package com.tksapec.ywidget.work

import androidx.work.ExistingWorkPolicy
import com.tksapec.ywidget.data.NewsItem
import com.tksapec.ywidget.data.REFRESH_ACTIVE_TIMEOUT_MILLIS
import com.tksapec.ywidget.data.RefreshResult
import com.tksapec.ywidget.data.WidgetSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshWorkerPolicyTest {
    @Test
    fun refreshWorkStartSchedulesCleanupAfterRunningState() = runBlocking {
        val calls = mutableListOf<String>()

        prepareRefreshWork(
            markRunning = { calls += "running" },
            enqueueCleanup = { calls += "cleanup" },
            shouldUpdateWidget = { true },
            updateWidget = { calls += "widget" },
        )

        assertEquals(listOf("running", "cleanup", "widget"), calls)
    }

    @Test
    fun refreshWorkStartSkipsWidgetUpdateWhenExistingNewsCanRemainVisible() = runBlocking {
        val calls = mutableListOf<String>()

        prepareRefreshWork(
            markRunning = { calls += "running" },
            enqueueCleanup = { calls += "cleanup" },
            shouldUpdateWidget = { false },
            updateWidget = { calls += "widget" },
        )

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
    fun finishRefreshClearsRemainingStateBeforeFinalRedraw() = runBlocking {
        val calls = mutableListOf<String>()
        val remainingState = WidgetSettings(
            newsRefreshing = true,
            weatherRefreshing = true,
            refreshQueued = true,
            refreshStartedAtMillis = 1_000L,
            lastRefreshFinishedAtMillis = 2_000L,
            lastRefreshResult = RefreshResult.Success,
        )
        val clearedState = WidgetSettings(
            lastRefreshFinishedAtMillis = 2_000L,
            lastRefreshResult = RefreshResult.Success,
        )
        val states = ArrayDeque(listOf(remainingState, clearedState))

        val result = finishRefreshAndRedraw(
            finishRefresh = { calls += "finish" },
            readSettings = { calls += "read"; states.removeFirst() },
            clearRefreshState = { calls += "clear" },
            redrawWidgets = { calls += "redraw"; true },
        )

        assertEquals(true, result)
        assertEquals(listOf("finish", "read", "clear", "read", "redraw"), calls)
    }

    @Test
    fun finishRefreshStillRedrawsWhenStateVerificationFails() = runBlocking {
        val calls = mutableListOf<String>()

        val result = finishRefreshAndRedraw(
            finishRefresh = { calls += "finish" },
            readSettings = { calls += "read"; error("read failed") },
            clearRefreshState = { calls += "clear" },
            redrawWidgets = { calls += "redraw"; true },
        )

        assertEquals(true, result)
        assertEquals(listOf("finish", "read", "redraw"), calls)
    }

    @Test
    fun finishRefreshStillRedrawsWhenForcedClearFails() = runBlocking {
        val calls = mutableListOf<String>()
        val remainingState = WidgetSettings(
            newsRefreshing = true,
            refreshStartedAtMillis = 1_000L,
            lastRefreshFinishedAtMillis = 2_000L,
            lastRefreshResult = RefreshResult.Success,
        )

        val result = finishRefreshAndRedraw(
            finishRefresh = { calls += "finish" },
            readSettings = { calls += "read"; remainingState },
            clearRefreshState = { calls += "clear"; error("clear failed") },
            redrawWidgets = { calls += "redraw"; true },
        )

        assertEquals(true, result)
        assertEquals(listOf("finish", "read", "clear", "redraw"), calls)
    }

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
