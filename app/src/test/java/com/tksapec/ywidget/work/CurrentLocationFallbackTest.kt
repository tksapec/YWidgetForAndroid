package com.tksapec.ywidget.work

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
        )

        assertEquals(current, selectCurrentWeatherTarget(true, current, settings))
    }

    @Test
    fun cachedLocationIsUsedWhenCurrentLocationFails() {
        val settings = WidgetSettings(
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 138.0,
            lastCurrentLocationLabel = "cached",
        )

        assertEquals(
            WeatherTarget(34.0, 138.0, "cached"),
            selectCurrentWeatherTarget(true, null, settings),
        )
    }

    @Test
    fun missingPermissionDoesNotUseCachedLocation() {
        val settings = WidgetSettings(lastCurrentLatitude = 34.0, lastCurrentLongitude = 138.0)

        val error = runCatching { selectCurrentWeatherTarget(false, null, settings) }.exceptionOrNull()

        assertEquals(LOCATION_PERMISSION_DENIED_MESSAGE, error?.message)
    }

    @Test
    fun missingCurrentAndCachedLocationShowsFixedLocationGuidance() {
        val error = runCatching {
            selectCurrentWeatherTarget(true, null, WidgetSettings())
        }.exceptionOrNull()

        assertEquals(CURRENT_LOCATION_UNAVAILABLE_MESSAGE, error?.message)
    }
}
