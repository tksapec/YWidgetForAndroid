package com.tksapec.ywidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSettingsTest {
    @Test
    fun refreshDueWhenNewsHasNeverSucceeded() {
        val settings = WidgetSettings(newsUpdatedAtMillis = 0L)

        assertTrue(settings.isRefreshDue(now = 60_000L))
    }

    @Test
    fun refreshDueWhenNewsIsOlderThanInterval() {
        val settings = WidgetSettings(
            updateIntervalMinutes = 60L,
            newsUpdatedAtMillis = 1_000L,
        )

        assertTrue(settings.isRefreshDue(now = 3_601_001L))
    }

    @Test
    fun refreshDueWhenWeatherHasNeverSucceeded() {
        val settings = WidgetSettings(
            updateIntervalMinutes = 60L,
            newsUpdatedAtMillis = 3_600_000L,
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Fixed,
            weatherUpdatedAtMillis = 0L,
        )

        assertTrue(settings.isRefreshDue(now = 3_600_001L))
    }

    @Test
    fun refreshDueWhenOnlyWeatherIsOlderThanInterval() {
        val settings = WidgetSettings(
            updateIntervalMinutes = 60L,
            newsUpdatedAtMillis = 3_600_000L,
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Fixed,
            weatherUpdatedAtMillis = 1_000L,
        )

        assertTrue(settings.isRefreshDue(now = 3_601_001L))
    }

    @Test
    fun disabledWeatherDoesNotMakeRefreshDue() {
        val settings = WidgetSettings(
            updateIntervalMinutes = 60L,
            newsUpdatedAtMillis = 3_600_000L,
            weatherEnabled = false,
            weatherLocationMode = WeatherLocationMode.Disabled,
            weatherUpdatedAtMillis = 0L,
        )

        assertFalse(settings.isRefreshDue(now = 3_600_001L))
    }

    @Test
    fun activeNewsRefreshIsTrueWithinTimeout() {
        val settings = WidgetSettings(
            newsRefreshing = true,
            refreshStartedAtMillis = 1_000L,
        )

        assertTrue(settings.isNewsRefreshingActive(now = 1_000L + REFRESH_ACTIVE_TIMEOUT_MILLIS - 1L))
    }

    @Test
    fun activeNewsRefreshIsFalseAfterTimeout() {
        val settings = WidgetSettings(
            newsRefreshing = true,
            refreshStartedAtMillis = 1_000L,
        )

        assertFalse(settings.isNewsRefreshingActive(now = 1_000L + REFRESH_ACTIVE_TIMEOUT_MILLIS))
    }

    @Test
    fun activeNewsRefreshIsFalseWithoutStartTime() {
        val settings = WidgetSettings(
            newsRefreshing = true,
            refreshStartedAtMillis = 0L,
        )

        assertFalse(settings.isNewsRefreshingActive(now = 60_000L))
    }

    @Test
    fun activeNewsRefreshIsFalseWhenNotRefreshing() {
        val settings = WidgetSettings(
            newsRefreshing = false,
            refreshStartedAtMillis = 1_000L,
        )

        assertFalse(settings.isNewsRefreshingActive(now = 2_000L))
    }

    @Test
    fun activeWeatherRefreshIsTrueWithinTimeout() {
        val settings = WidgetSettings(
            weatherRefreshing = true,
            refreshStartedAtMillis = 1_000L,
        )

        assertTrue(settings.isWeatherRefreshingActive(now = 1_000L + REFRESH_ACTIVE_TIMEOUT_MILLIS - 1L))
    }

    @Test
    fun activeWeatherRefreshIsFalseAfterTimeout() {
        val settings = WidgetSettings(
            weatherRefreshing = true,
            refreshStartedAtMillis = 1_000L,
        )

        assertFalse(settings.isWeatherRefreshingActive(now = 1_000L + REFRESH_ACTIVE_TIMEOUT_MILLIS))
    }

    @Test
    fun activeWeatherRefreshIsFalseWithoutStartTime() {
        val settings = WidgetSettings(
            weatherRefreshing = true,
            refreshStartedAtMillis = 0L,
        )

        assertFalse(settings.isWeatherRefreshingActive(now = 60_000L))
    }

    @Test
    fun activeRefreshQueueIsTrueWithinTimeout() {
        val settings = WidgetSettings(
            refreshQueued = true,
            refreshStartedAtMillis = 1_000L,
        )

        assertTrue(settings.isRefreshQueuedActive(now = 1_000L + REFRESH_ACTIVE_TIMEOUT_MILLIS - 1L))
    }

    @Test
    fun activeRefreshQueueIsFalseAfterTimeout() {
        val settings = WidgetSettings(
            refreshQueued = true,
            refreshStartedAtMillis = 1_000L,
        )

        assertFalse(settings.isRefreshQueuedActive(now = 1_000L + REFRESH_ACTIVE_TIMEOUT_MILLIS))
    }

    @Test
    fun weatherUnavailableErrorUsesUserFacingMessage() {
        val message = userFacingWeatherErrorMessage(IllegalStateException("jxhk:UNAVAILABLE"))

        assertEquals(CURRENT_LOCATION_UNAVAILABLE_MESSAGE, message)
    }

    @Test
    fun weatherErrorKeepsNonBlankMessage() {
        val message = userFacingWeatherErrorMessage(IllegalStateException("\u56FA\u5B9A\u5730\u57DF\u672A\u8A2D\u5B9A"))

        assertEquals("\u56FA\u5B9A\u5730\u57DF\u672A\u8A2D\u5B9A", message)
    }

    @Test
    fun blankWeatherErrorUsesGenericMessage() {
        val message = userFacingWeatherErrorMessage(IllegalStateException())

        assertEquals("\u5929\u6C17\u53D6\u5F97\u5931\u6557", message)
    }

    @Test
    fun internalWeatherErrorUsesGenericMessage() {
        val message = userFacingWeatherErrorMessage(IllegalStateException("Weather request failed: HTTP 500"))

        assertEquals("\u5929\u6C17\u53D6\u5F97\u5931\u6557", message)
    }

    @Test
    fun newsFetchSummaryKeepsSuccessfulNewsWhenOneCategoryFails() {
        val news = NewsItem(title = "A", url = "https://example.com/a")
        val summary = summarizeNewsFetchResults(
            listOf(
                Result.success(listOf(news)),
                Result.failure(IllegalStateException("failed")),
            ),
        )

        assertTrue(summary.hasNews)
        assertEquals(listOf(news), summary.news)
        assertEquals(1, summary.failedCategoryCount)
        assertEquals(1, summary.failures.size)
    }

    @Test
    fun newsFetchSummaryTreatsEmptySuccessfulCategoryAsFailedForDisplay() {
        val summary = summarizeNewsFetchResults(
            listOf(
                Result.success(emptyList()),
                Result.success(emptyList()),
            ),
        )

        assertFalse(summary.hasNews)
        assertEquals(2, summary.failedCategoryCount)
        assertTrue(summary.failures.isEmpty())
    }

    @Test
    fun launcherSlotNormalizationKeepsEmptySlots() {
        val app = LauncherAppShortcut(
            displayName = "Calendar",
            packageName = "com.example.calendar",
        )

        val slots = normalizeLauncherAppSlots(
            listOf(LauncherAppSlot(slotIndex = 2, app = app)),
        )

        assertEquals(3, slots.size)
        assertNull(slots[0].app)
        assertNull(slots[1].app)
        assertEquals(app, slots[2].app)
    }
}
