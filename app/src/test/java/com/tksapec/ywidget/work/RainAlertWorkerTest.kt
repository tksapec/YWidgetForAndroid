package com.tksapec.ywidget.work

import com.tksapec.ywidget.data.WeatherLocationMode
import com.tksapec.ywidget.data.WidgetSettings
import com.tksapec.ywidget.network.YahooRainHttpException
import com.tksapec.ywidget.network.YahooRainParseException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainAlertWorkerTest {
    @Test
    fun fixedTargetUsesResolvedCoordinates() {
        val settings = WidgetSettings(
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Fixed,
            fixedLatitude = 35.0,
            fixedLongitude = 135.0,
        )

        val target = selectRainTarget(settings, currentTarget = null, now = 10_000L)

        assertEquals(35.0, target!!.latitude, 0.0001)
        assertEquals(135.0, target.longitude, 0.0001)
    }

    @Test
    fun currentTargetPrefersFreshLiveLocation() {
        val settings = WidgetSettings(
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Current,
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 134.0,
            lastCurrentLocationAtMillis = 10_000L,
        )
        val live = RainTarget(35.0, 135.0)

        assertEquals(live, selectRainTarget(settings, currentTarget = live, now = 10_000L))
    }

    @Test
    fun currentTargetFallsBackToFreshCachedLocation() {
        val settings = WidgetSettings(
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Current,
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 134.0,
            lastCurrentLocationAtMillis = 10_000L,
        )

        val target = selectRainTarget(settings, currentTarget = null, now = 20_000L)

        assertEquals(RainTarget(34.0, 134.0), target)
    }

    @Test
    fun disabledWeatherHasNoRainTarget() {
        val settings = WidgetSettings(
            weatherEnabled = false,
            weatherLocationMode = WeatherLocationMode.Disabled,
        )

        assertNull(selectRainTarget(settings, currentTarget = null, now = 10_000L))
    }

    @Test
    fun periodicIntervalIsFifteenMinutes() {
        assertEquals(15L, rainAlertPeriodicIntervalMinutes())
    }

    @Test
    fun retryPolicyRetriesTimeoutAndServerErrorsButNotBadRequestOrParseErrors() {
        assertTrue(isTransientRainFailure(SocketTimeoutException()))
        assertTrue(isTransientRainFailure(YahooRainHttpException(503)))
        assertTrue(isTransientRainFailure(YahooRainHttpException(429)))
        assertFalse(isTransientRainFailure(YahooRainHttpException(400)))
        assertFalse(isTransientRainFailure(YahooRainParseException("bad json")))
    }
}
