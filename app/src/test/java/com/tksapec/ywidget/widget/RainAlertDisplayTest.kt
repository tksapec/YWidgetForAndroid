package com.tksapec.ywidget.widget

import com.tksapec.ywidget.data.RAINING_ALERT_MAX_AGE_MILLIS
import com.tksapec.ywidget.data.RainAlertLevel
import com.tksapec.ywidget.data.WidgetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RainAlertDisplayTest {
    @Test
    fun noAlertProducesNoBanner() {
        assertNull(rainAlertDisplay(WidgetSettings(), now = 10_000L))
    }

    @Test
    fun staleRainingAlertProducesNoBannerAfterFifteenMinutes() {
        val settings = settings(
            level = RainAlertLevel.Raining,
            minutes = 0,
            rainfall = 2.0,
            updatedAt = 1_000L,
            rainAt = 1_000L,
        )

        assertNull(rainAlertDisplay(settings, now = 1_001L + RAINING_ALERT_MAX_AGE_MILLIS))
    }

    @Test
    fun currentRainIsClearlyLabeled() {
        val display = rainAlertDisplay(
            settings(
                level = RainAlertLevel.Raining,
                minutes = 0,
                rainfall = 3.25,
                updatedAt = 10_000L,
                rainAt = 10_000L,
            ),
            now = 10_000L,
        )!!

        assertEquals(RainAlertLevel.Raining, display.level)
        assertEquals("☔ 現在やや強い雨が降っています 3.3 mm/h", display.text)
        assertFalse(display.isHeavy)
    }

    @Test
    fun countdownUsesAbsoluteRainTimeInsteadOfStoredMinutes() {
        val rainAt = 20 * 60_000L
        val display = rainAlertDisplay(
            settings(
                level = RainAlertLevel.Imminent,
                minutes = 15,
                rainfall = 1.2,
                updatedAt = 10 * 60_000L,
                rainAt = rainAt,
            ),
            now = 15 * 60_000L,
        )!!

        assertEquals("☔ 5分以内に雨 1.2 mm/h", display.text)
    }

    @Test
    fun nearbyRainAtCurrentTimeSaysCurrentlyRainingNearby() {
        val now = 10_000L
        val display = rainAlertDisplay(
            settings(
                level = RainAlertLevel.Imminent,
                minutes = 0,
                rainfall = 1.5,
                nearbyOnly = true,
                updatedAt = now,
                rainAt = now,
            ),
            now = now,
        )!!

        assertEquals("☔ 周辺で現在雨 1.5 mm/h", display.text)
    }

    @Test
    fun sixtyMinuteCenterWatchUsesPossibilityWording() {
        val now = 10_000L
        val display = rainAlertDisplay(
            settings(
                level = RainAlertLevel.Watch,
                minutes = 60,
                rainfall = 0.8,
                updatedAt = now,
                rainAt = now + 60 * 60_000L,
            ),
            now = now,
        )!!

        assertTrue(display.text.contains("60分以内に雨の可能性"))
        assertFalse(display.isHeavy)
    }

    @Test
    fun heavyRainIsMarkedForStrongBannerEvenWhenForecastIsLater() {
        val now = 10_000L
        val display = rainAlertDisplay(
            settings(
                level = RainAlertLevel.Watch,
                minutes = 50,
                rainfall = 12.0,
                updatedAt = now,
                rainAt = now + 50 * 60_000L,
            ),
            now = now,
        )!!

        assertTrue(display.isHeavy)
        assertTrue(display.text.contains("強い雨"))
    }

    private fun settings(
        level: RainAlertLevel,
        minutes: Int?,
        rainfall: Double?,
        nearbyOnly: Boolean = false,
        updatedAt: Long,
        rainAt: Long? = null,
    ) = WidgetSettings(
        rainAlertLevel = level,
        rainAlertMinutesUntilRain = minutes,
        rainAlertRainAtMillis = rainAt,
        rainAlertRainfallMmPerHour = rainfall,
        rainAlertNearbyOnly = nearbyOnly,
        rainAlertUpdatedAtMillis = updatedAt,
    )
}
