package com.tksapec.ywidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainAlertTest {
    @Test
    fun probePointsContainCenterAndEightSurroundingPoints() {
        val points = buildRainProbePoints(latitude = 35.0, longitude = 135.0)

        assertEquals(9, points.size)
        assertEquals(RAIN_CENTER_PROBE_ID, points.first().id)
        assertTrue(points.first().isCenter)
        assertEquals(9, points.map { it.id }.distinct().size)
        assertTrue(points.first { it.id == "n" }.latitude > 35.0)
        assertTrue(points.first { it.id == "e" }.longitude > 135.0)
        assertTrue(points.first { it.id == "s" }.latitude < 35.0)
        assertTrue(points.first { it.id == "w" }.longitude < 135.0)
    }

    @Test
    fun rainfallBelowThresholdDoesNotAlert() {
        val state = evaluateRainAlert(
            observations = listOf(
                obs(RAIN_CENTER_PROBE_ID, RainObservationType.Observation, 0, 0.49),
                obs(RAIN_CENTER_PROBE_ID, RainObservationType.Forecast, 10, 0.49),
                obs("w", RainObservationType.Forecast, 10, 0.49),
            ),
            evaluatedAtMillis = 10_000L,
        )

        assertEquals(RainAlertLevel.None, state.level)
    }

    @Test
    fun exactThresholdAtCenterObservationMeansRaining() {
        val state = evaluateRainAlert(
            observations = listOf(
                obs(RAIN_CENTER_PROBE_ID, RainObservationType.Observation, 0, 0.5),
            ),
            evaluatedAtMillis = 10_000L,
        )

        assertEquals(RainAlertLevel.Raining, state.level)
        assertEquals(0, state.minutesUntilRain)
        assertEquals(0.5, state.rainfallMmPerHour!!, 0.0001)
        assertFalse(state.nearbyOnly)
    }

    @Test
    fun centerForecastAtFifteenMinutesIsImminent() {
        val state = evaluateRainAlert(
            observations = centerForecast(minutes = 15, rainfall = 1.2),
            evaluatedAtMillis = 10_000L,
        )

        assertEquals(RainAlertLevel.Imminent, state.level)
        assertEquals(15, state.minutesUntilRain)
        assertFalse(state.nearbyOnly)
    }

    @Test
    fun centerForecastAtThirtyMinutesIsSoon() {
        val state = evaluateRainAlert(
            observations = centerForecast(minutes = 30, rainfall = 2.0),
            evaluatedAtMillis = 10_000L,
        )

        assertEquals(RainAlertLevel.Soon, state.level)
        assertEquals(30, state.minutesUntilRain)
    }

    @Test
    fun centerForecastAtSixtyMinutesIsWatch() {
        val state = evaluateRainAlert(
            observations = centerForecast(minutes = 60, rainfall = 0.8),
            evaluatedAtMillis = 10_000L,
        )

        assertEquals(RainAlertLevel.Watch, state.level)
        assertEquals(60, state.minutesUntilRain)
    }

    @Test
    fun nearbyRainAtThirtyMinutesTriggersNearbyAlert() {
        val state = evaluateRainAlert(
            observations = listOf(
                obs(RAIN_CENTER_PROBE_ID, RainObservationType.Observation, 0, 0.0),
                obs("w", RainObservationType.Forecast, 30, 1.5),
            ),
            evaluatedAtMillis = 10_000L,
        )

        assertEquals(RainAlertLevel.Soon, state.level)
        assertEquals(30, state.minutesUntilRain)
        assertTrue(state.nearbyOnly)
    }

    @Test
    fun nearbyRainBeyondThirtyMinutesDoesNotAlert() {
        val state = evaluateRainAlert(
            observations = listOf(
                obs(RAIN_CENTER_PROBE_ID, RainObservationType.Observation, 0, 0.0),
                obs("w", RainObservationType.Forecast, 35, 5.0),
            ),
            evaluatedAtMillis = 10_000L,
        )

        assertEquals(RainAlertLevel.None, state.level)
    }

    @Test
    fun moreUrgentNearbyRainWinsOverLaterCenterWatch() {
        val state = evaluateRainAlert(
            observations = listOf(
                obs(RAIN_CENTER_PROBE_ID, RainObservationType.Observation, 0, 0.0),
                obs(RAIN_CENTER_PROBE_ID, RainObservationType.Forecast, 50, 1.0),
                obs("w", RainObservationType.Forecast, 10, 2.0),
            ),
            evaluatedAtMillis = 10_000L,
        )

        assertEquals(RainAlertLevel.Imminent, state.level)
        assertEquals(10, state.minutesUntilRain)
        assertTrue(state.nearbyOnly)
    }

    @Test
    fun alertFreshnessExpiresAfterFortyFiveMinutes() {
        assertTrue(isRainAlertFresh(updatedAtMillis = 1_000L, nowMillis = 1_000L + RAIN_ALERT_MAX_AGE_MILLIS))
        assertFalse(isRainAlertFresh(updatedAtMillis = 1_000L, nowMillis = 1_001L + RAIN_ALERT_MAX_AGE_MILLIS))
    }

    private fun centerForecast(minutes: Int, rainfall: Double): List<RainObservation> = listOf(
        obs(RAIN_CENTER_PROBE_ID, RainObservationType.Observation, 0, 0.0),
        obs(RAIN_CENTER_PROBE_ID, RainObservationType.Forecast, minutes, rainfall),
    )

    private fun obs(
        probeId: String,
        type: RainObservationType,
        minutes: Int,
        rainfall: Double,
    ): RainObservation = RainObservation(
        probeId = probeId,
        type = type,
        timestampMillis = BASE_TIME + minutes * 60_000L,
        rainfallMmPerHour = rainfall,
    )

    companion object {
        private const val BASE_TIME = 1_000_000L
    }
}
