package com.tksapec.ywidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(BASE_TIME, state.rainAtMillis)
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
        assertEquals(BASE_TIME + 15 * 60_000L, state.rainAtMillis)
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
    fun centerObservationIsRequiredForEvaluation() {
        val state = evaluateRainAlert(
            observations = listOf(obs("w", RainObservationType.Forecast, 10, 2.0)),
            evaluatedAtMillis = 10_000L,
        )

        assertEquals(RainAlertLevel.None, state.level)
    }

    @Test
    fun rainingAlertExpiresAfterFifteenMinutes() {
        assertTrue(
            isRainAlertFresh(
                RainAlertLevel.Raining,
                updatedAtMillis = 1_000L,
                nowMillis = 1_000L + RAINING_ALERT_MAX_AGE_MILLIS,
            ),
        )
        assertFalse(
            isRainAlertFresh(
                RainAlertLevel.Raining,
                updatedAtMillis = 1_000L,
                nowMillis = 1_001L + RAINING_ALERT_MAX_AGE_MILLIS,
            ),
        )
    }

    @Test
    fun watchAlertCanRemainFreshForFortyFiveMinutesWhileItIsStillWatch() {
        assertTrue(
            isRainAlertFresh(
                RainAlertLevel.Watch,
                updatedAtMillis = 1_000L,
                nowMillis = 1_000L + RAIN_ALERT_MAX_AGE_MILLIS,
            ),
        )
    }

    @Test
    fun watchForecastUsesImminentFreshnessAfterCountdownEscalates() {
        val updatedAt = 1_000L
        val rainAt = updatedAt + 50 * 60_000L
        val now = updatedAt + 40 * 60_000L

        assertEquals(
            RainAlertLevel.Imminent,
            effectiveRainAlertLevel(
                storedLevel = RainAlertLevel.Watch,
                rainAtMillis = rainAt,
                fallbackMinutesUntilRain = 50,
                nowMillis = now,
            ),
        )
        assertFalse(
            isEffectiveRainAlertFresh(
                storedLevel = RainAlertLevel.Watch,
                updatedAtMillis = updatedAt,
                rainAtMillis = rainAt,
                fallbackMinutesUntilRain = 50,
                nowMillis = now,
            ),
        )
    }

    @Test
    fun forecastIsHiddenMoreThanFiveMinutesAfterPredictedTime() {
        val updatedAt = 1_000L
        val rainAt = updatedAt + 10 * 60_000L

        assertTrue(
            isEffectiveRainAlertFresh(
                storedLevel = RainAlertLevel.Imminent,
                updatedAtMillis = updatedAt,
                rainAtMillis = rainAt,
                fallbackMinutesUntilRain = 10,
                nowMillis = rainAt + 5 * 60_000L,
            ),
        )
        assertFalse(
            isEffectiveRainAlertFresh(
                storedLevel = RainAlertLevel.Imminent,
                updatedAtMillis = updatedAt,
                rainAtMillis = rainAt,
                fallbackMinutesUntilRain = 10,
                nowMillis = rainAt + 5 * 60_000L + 1L,
            ),
        )
    }

    @Test
    fun shortForecastExpiryIsCappedAtFiveMinutesAfterPredictedTime() {
        val updatedAt = 1_000L
        val rainAt = updatedAt + 10 * 60_000L
        val state = RainAlertState(
            level = RainAlertLevel.Imminent,
            minutesUntilRain = 10,
            rainAtMillis = rainAt,
            rainfallMmPerHour = 1.0,
            updatedAtMillis = updatedAt,
        )

        assertEquals(
            rainAt + 5 * 60_000L + 1_000L,
            rainAlertExpiryAtMillis(state),
        )
    }

    @Test
    fun fiftyMinuteWatchExpiresWhenSoonFreshnessBecomesTooOld() {
        val updatedAt = 1_000L
        val state = RainAlertState(
            level = RainAlertLevel.Watch,
            minutesUntilRain = 50,
            rainAtMillis = updatedAt + 50 * 60_000L,
            rainfallMmPerHour = 1.0,
            updatedAtMillis = updatedAt,
        )

        assertEquals(
            updatedAt + SOON_ALERT_MAX_AGE_MILLIS + 1_000L,
            rainAlertExpiryAtMillis(state),
        )
    }

    @Test
    fun remainingMinutesAreRecomputedFromAbsoluteRainTime() {
        val rainAt = 20 * 60_000L
        assertEquals(10, remainingRainMinutes(rainAt, nowMillis = 10 * 60_000L))
        assertEquals(1, remainingRainMinutes(rainAt, nowMillis = rainAt - 1L))
        assertEquals(0, remainingRainMinutes(rainAt, nowMillis = rainAt + 1L))
        assertNull(remainingRainMinutes(null, nowMillis = 0L))
    }

    @Test
    fun intensityLabelsOnlyCallOutMeaningfulRain() {
        assertNull(rainIntensityLabel(0.8))
        assertEquals("やや強い雨", rainIntensityLabel(2.0))
        assertEquals("強い雨", rainIntensityLabel(10.0))
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
