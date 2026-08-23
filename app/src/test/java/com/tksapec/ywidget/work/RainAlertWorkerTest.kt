package com.tksapec.ywidget.work

import com.tksapec.ywidget.data.RAIN_CENTER_PROBE_ID
import com.tksapec.ywidget.data.RainObservation
import com.tksapec.ywidget.data.RainObservationType
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
        val settings = rainSettings(
            weatherLocationMode = WeatherLocationMode.Fixed,
            fixedLocationQuery = "大阪市",
            fixedLatitude = 35.0,
            fixedLongitude = 135.0,
        )

        val target = selectRainTarget(settings, currentTarget = null, now = 10_000L)

        assertEquals(35.0, target!!.latitude, 0.0001)
        assertEquals(135.0, target.longitude, 0.0001)
    }

    @Test
    fun currentTargetPrefersNewerLiveLocation() {
        val settings = rainSettings(
            weatherLocationMode = WeatherLocationMode.Current,
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 134.0,
            lastCurrentLocationAtMillis = 10_000L,
        )
        val live = RainTarget(35.0, 135.0, locationAtMillis = 11_000L)

        assertEquals(live, selectRainTarget(settings, currentTarget = live, now = 12_000L))
    }

    @Test
    fun currentTargetPrefersNewerCachedLocationOverOlderLiveResult() {
        val settings = rainSettings(
            weatherLocationMode = WeatherLocationMode.Current,
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 134.0,
            lastCurrentLocationAtMillis = 10_000L,
        )
        val olderLive = RainTarget(35.0, 135.0, locationAtMillis = 9_000L)

        assertEquals(
            RainTarget(34.0, 134.0, 10_000L),
            selectRainTarget(settings, currentTarget = olderLive, now = 12_000L),
        )
    }

    @Test
    fun currentTargetFallsBackToFreshCachedLocation() {
        val settings = rainSettings(
            weatherLocationMode = WeatherLocationMode.Current,
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 134.0,
            lastCurrentLocationAtMillis = 10_000L,
        )

        val target = selectRainTarget(settings, currentTarget = null, now = 20_000L)

        assertEquals(RainTarget(34.0, 134.0, 10_000L), target)
    }

    @Test
    fun currentTargetAcceptsCachedLocationAtFifteenMinuteBoundary() {
        val now = 2_000_000L
        val cachedAt = now - 15 * 60_000L
        val settings = rainSettings(
            weatherLocationMode = WeatherLocationMode.Current,
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 134.0,
            lastCurrentLocationAtMillis = cachedAt,
        )

        assertEquals(
            RainTarget(34.0, 134.0, cachedAt),
            selectRainTarget(settings, currentTarget = null, now = now),
        )
    }

    @Test
    fun currentTargetRejectsCachedLocationOlderThanFifteenMinutes() {
        val now = 2_000_000L
        val settings = rainSettings(
            weatherLocationMode = WeatherLocationMode.Current,
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 134.0,
            lastCurrentLocationAtMillis = now - 15 * 60_000L - 1L,
        )

        assertNull(selectRainTarget(settings, currentTarget = null, now = now))
    }

    @Test
    fun currentTargetDoesNotUseCachedCoordinatesWithoutLocationPermission() {
        val settings = rainSettings(
            weatherLocationMode = WeatherLocationMode.Current,
            lastCurrentLatitude = 34.0,
            lastCurrentLongitude = 134.0,
            lastCurrentLocationAtMillis = 10_000L,
        )

        assertNull(
            selectRainTarget(
                settings = settings,
                currentTarget = null,
                now = 20_000L,
                currentLocationPermissionGranted = false,
            ),
        )
    }

    @Test
    fun disabledWeatherLocationHasNoRainTarget() {
        val settings = rainSettings(weatherLocationMode = WeatherLocationMode.Disabled)

        assertNull(selectRainTarget(settings, currentTarget = null, now = 10_000L))
    }

    @Test
    fun fixedSourceKeyIsStableWhileCoordinatesAreBeingResolved() {
        val unresolved = rainSettings(
            weatherLocationMode = WeatherLocationMode.Fixed,
            fixedLocationQuery = "大阪市",
        )
        val resolved = unresolved.copy(
            fixedLatitude = 34.6937,
            fixedLongitude = 135.5023,
        )

        assertEquals(rainSourceKey(unresolved), rainSourceKey(resolved))
    }

    @Test
    fun changingFixedQueryChangesRainSourceKey() {
        val before = rainSettings(
            weatherLocationMode = WeatherLocationMode.Fixed,
            fixedLocationQuery = "大阪市",
        )
        val after = before.copy(fixedLocationQuery = "神戸市")

        assertTrue(rainSourceKey(before) != rainSourceKey(after))
    }

    @Test
    fun changingLocationModeChangesRainSourceKey() {
        val fixed = rainSettings(
            weatherLocationMode = WeatherLocationMode.Fixed,
            fixedLocationQuery = "大阪市",
        )
        val current = fixed.copy(weatherLocationMode = WeatherLocationMode.Current)

        assertTrue(rainSourceKey(fixed) != rainSourceKey(current))
    }

    @Test
    fun periodicIntervalIsFifteenMinutes() {
        assertEquals(15L, rainAlertPeriodicIntervalMinutes())
    }

    @Test
    fun currentCenterObservationTimelineIsAccepted() {
        val now = 1_000_000L
        assertTrue(
            isRainObservationTimelineUsable(
                observations = listOf(centerObservation(now - 5 * 60_000L)),
                evaluatedAtMillis = now,
            ),
        )
    }

    @Test
    fun staleCenterObservationTimelineIsRejected() {
        val now = 1_000_000L
        assertFalse(
            isRainObservationTimelineUsable(
                observations = listOf(centerObservation(now - 16 * 60_000L)),
                evaluatedAtMillis = now,
            ),
        )
    }

    @Test
    fun implausiblyFutureCenterObservationTimelineIsRejected() {
        val now = 1_000_000L
        assertFalse(
            isRainObservationTimelineUsable(
                observations = listOf(centerObservation(now + 6 * 60_000L)),
                evaluatedAtMillis = now,
            ),
        )
    }

    @Test
    fun retryPolicyRetriesTimeoutAndServerErrorsButNotBadRequestOrParseErrors() {
        assertTrue(isTransientRainFailure(SocketTimeoutException()))
        assertTrue(isTransientRainFailure(YahooRainHttpException(503)))
        assertTrue(isTransientRainFailure(YahooRainHttpException(429)))
        assertFalse(isTransientRainFailure(YahooRainHttpException(400)))
        assertFalse(isTransientRainFailure(YahooRainParseException("bad json")))
    }

    private fun centerObservation(timestampMillis: Long) = RainObservation(
        probeId = RAIN_CENTER_PROBE_ID,
        type = RainObservationType.Observation,
        timestampMillis = timestampMillis,
        rainfallMmPerHour = 0.0,
    )

    private fun rainSettings(
        weatherLocationMode: WeatherLocationMode,
        fixedLocationQuery: String = "",
        fixedLatitude: Double? = null,
        fixedLongitude: Double? = null,
        lastCurrentLatitude: Double? = null,
        lastCurrentLongitude: Double? = null,
        lastCurrentLocationAtMillis: Long = 0L,
    ) = WidgetSettings(
        weatherEnabled = weatherLocationMode != WeatherLocationMode.Disabled,
        weatherLocationMode = weatherLocationMode,
        rainAlertEnabled = true,
        fixedLocationQuery = fixedLocationQuery,
        fixedLatitude = fixedLatitude,
        fixedLongitude = fixedLongitude,
        lastCurrentLatitude = lastCurrentLatitude,
        lastCurrentLongitude = lastCurrentLongitude,
        lastCurrentLocationAtMillis = lastCurrentLocationAtMillis,
    )
}
