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
