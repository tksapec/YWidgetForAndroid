package com.tksapec.ywidget.work

import com.tksapec.ywidget.data.CURRENT_LOCATION_CACHE_MAX_AGE_MILLIS
import com.tksapec.ywidget.data.CURRENT_LOCATION_UNAVAILABLE_MESSAGE
import com.tksapec.ywidget.data.LOCATION_PERMISSION_DENIED_MESSAGE
import com.tksapec.ywidget.data.WidgetSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentLocationFallbackTest {
    @Test
    fun currentLocationTakesPriorityOverCachedLocation() {
        val current = WeatherTarget(35.0, 139.0, "current")
        val settings = WidgetSettings(
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 138.0,
            lastCurrentLocationLabel = "cached",
            lastCurrentLocationAtMillis = 9_000L,
        )

        assertEquals(current, selectCurrentWeatherTarget(true, current, settings, now = 10_000L))
    }

    @Test
    fun recentCachedLocationIsUsedWhenCurrentLocationFails() {
        val settings = WidgetSettings(
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 138.0,
            lastCurrentLocationLabel = "cached",
            lastCurrentLocationAtMillis = 9_000L,
        )

        assertEquals(
            WeatherTarget(34.0, 138.0, "cached"),
            selectCurrentWeatherTarget(true, null, settings, now = 10_000L),
        )
    }

    @Test
    fun staleCachedLocationIsRejected() {
        val now = CURRENT_LOCATION_CACHE_MAX_AGE_MILLIS + 10_001L
        val settings = WidgetSettings(
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 138.0,
            lastCurrentLocationAtMillis = 10_000L,
        )

        val error = runCatching {
            selectCurrentWeatherTarget(true, null, settings, now)
        }.exceptionOrNull()

        assertEquals(CURRENT_LOCATION_UNAVAILABLE_MESSAGE, error?.message)
    }

    @Test
    fun missingPermissionDoesNotUseCachedLocation() {
        val settings = WidgetSettings(
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 138.0,
            lastCurrentLocationAtMillis = 9_000L,
        )

        val error = runCatching {
            selectCurrentWeatherTarget(false, null, settings, now = 10_000L)
        }.exceptionOrNull()

        assertEquals(LOCATION_PERMISSION_DENIED_MESSAGE, error?.message)
    }

    @Test
    fun missingCurrentAndCachedLocationShowsFixedLocationGuidance() {
        val error = runCatching {
            selectCurrentWeatherTarget(true, null, WidgetSettings(), now = 10_000L)
        }.exceptionOrNull()

        assertEquals(CURRENT_LOCATION_UNAVAILABLE_MESSAGE, error?.message)
    }
}
