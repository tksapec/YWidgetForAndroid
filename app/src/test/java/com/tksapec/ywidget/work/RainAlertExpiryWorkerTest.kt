package com.tksapec.ywidget.work

import com.tksapec.ywidget.data.RAIN_ALERT_MAX_AGE_MILLIS
import com.tksapec.ywidget.data.RainAlertLevel
import com.tksapec.ywidget.data.WidgetSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainAlertExpiryWorkerTest {
    @Test
    fun matchingStaleAlertShouldExpire() {
        val settings = WidgetSettings(
            rainAlertLevel = RainAlertLevel.Soon,
            rainAlertUpdatedAtMillis = 1_000L,
        )

        assertTrue(
            shouldExpireRainAlert(
                settings = settings,
                expectedUpdatedAtMillis = 1_000L,
                nowMillis = 1_001L + RAIN_ALERT_MAX_AGE_MILLIS,
            ),
        )
    }

    @Test
    fun newerAlertMustNotBeClearedByOldExpiryWork() {
        val settings = WidgetSettings(
            rainAlertLevel = RainAlertLevel.Imminent,
            rainAlertUpdatedAtMillis = 2_000L,
        )

        assertFalse(
            shouldExpireRainAlert(
                settings = settings,
                expectedUpdatedAtMillis = 1_000L,
                nowMillis = 1_001L + RAIN_ALERT_MAX_AGE_MILLIS,
            ),
        )
    }

    @Test
    fun freshAlertDoesNotExpireEarly() {
        val settings = WidgetSettings(
            rainAlertLevel = RainAlertLevel.Watch,
            rainAlertUpdatedAtMillis = 1_000L,
        )

        assertFalse(
            shouldExpireRainAlert(
                settings = settings,
                expectedUpdatedAtMillis = 1_000L,
                nowMillis = 1_000L + RAIN_ALERT_MAX_AGE_MILLIS,
            ),
        )
    }

    @Test
    fun watchThatHasEscalatedToImminentExpiresByImminentFreshness() {
        val updatedAt = 1_000L
        val settings = WidgetSettings(
            rainAlertLevel = RainAlertLevel.Watch,
            rainAlertMinutesUntilRain = 50,
            rainAlertRainAtMillis = updatedAt + 50 * 60_000L,
            rainAlertUpdatedAtMillis = updatedAt,
        )

        assertTrue(
            shouldExpireRainAlert(
                settings = settings,
                expectedUpdatedAtMillis = updatedAt,
                nowMillis = updatedAt + 40 * 60_000L,
            ),
        )
    }

    @Test
    fun noAlertDoesNotNeedExpiryRedraw() {
        val settings = WidgetSettings(
            rainAlertLevel = RainAlertLevel.None,
            rainAlertUpdatedAtMillis = 1_000L,
        )

        assertFalse(
            shouldExpireRainAlert(
                settings = settings,
                expectedUpdatedAtMillis = 1_000L,
                nowMillis = 1_001L + RAIN_ALERT_MAX_AGE_MILLIS,
            ),
        )
    }
}
