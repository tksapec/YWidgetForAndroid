package com.tksapec.ywidget.widget

import com.tksapec.ywidget.data.RAIN_ALERT_MAX_AGE_MILLIS
import com.tksapec.ywidget.data.RainAlertLevel
import com.tksapec.ywidget.data.WidgetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainAlertDisplayTest {
    @Test
    fun noAlertProducesNoBanner() {
        assertNull(rainAlertDisplay(WidgetSettings(), now = 10_000L))
    }

    @Test
    fun staleAlertProducesNoBanner() {
        val settings = settings(
            level = RainAlertLevel.Imminent,
            minutes = 10,
            rainfall = 2.0,
            updatedAt = 1_000L,
        )

        assertNull(rainAlertDisplay(settings, now = 1_001L + RAIN_ALERT_MAX_AGE_MILLIS))
    }

    @Test
    fun currentRainIsClearlyLabeled() {
        val display = rainAlertDisplay(
            settings(
                level = RainAlertLevel.Raining,
                minutes = 0,
                rainfall = 3.25,
                updatedAt = 10_000L,
            ),
            now = 10_000L,
        )!!

        assertEquals(RainAlertLevel.Raining, display.level)
        assertEquals("☔ 現在雨が降っています 3.3 mm/h", display.text)
    }

    @Test
    fun centerImminentAlertUsesActualMinutes() {
        val display = rainAlertDisplay(
            settings(
                level = RainAlertLevel.Imminent,
                minutes = 10,
                rainfall = 1.2,
                updatedAt = 10_000L,
            ),
            now = 10_000L,
        )!!

        assertEquals("☔ 10分以内に雨 1.2 mm/h", display.text)
    }

    @Test
    fun nearbyAlertSaysNearbyRatherThanClaimingCenterRain() {
        val display = rainAlertDisplay(
            settings(
                level = RainAlertLevel.Soon,
                minutes = 25,
                rainfall = 1.5,
                nearbyOnly = true,
                updatedAt = 10_000L,
            ),
            now = 10_000L,
        )!!

        assertEquals("☔ 周辺で25分以内に雨 1.5 mm/h", display.text)
    }

    @Test
    fun sixtyMinuteCenterWatchUsesPossibilityWording() {
        val display = rainAlertDisplay(
            settings(
                level = RainAlertLevel.Watch,
                minutes = 60,
                rainfall = 0.8,
                updatedAt = 10_000L,
            ),
            now = 10_000L,
        )!!

        assertTrue(display.text.contains("60分以内に雨の可能性"))
    }

    private fun settings(
        level: RainAlertLevel,
        minutes: Int?,
        rainfall: Double?,
        nearbyOnly: Boolean = false,
        updatedAt: Long,
    ) = WidgetSettings(
        rainAlertLevel = level,
        rainAlertMinutesUntilRain = minutes,
        rainAlertRainfallMmPerHour = rainfall,
        rainAlertNearbyOnly = nearbyOnly,
        rainAlertUpdatedAtMillis = updatedAt,
    )
}
