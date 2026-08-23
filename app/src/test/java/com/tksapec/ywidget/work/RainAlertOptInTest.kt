package com.tksapec.ywidget.work

import com.tksapec.ywidget.data.WeatherLocationMode
import com.tksapec.ywidget.data.WidgetSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainAlertOptInTest {
    @Test
    fun rainAlertIsDisabledByDefaultEvenWhenWeatherLocationIsConfigured() {
        val settings = WidgetSettings(
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Fixed,
            fixedLocationQuery = "大阪市",
        )

        assertFalse(isRainAlertConfigured(settings))
    }

    @Test
    fun rainAlertRequiresExplicitOptInAndAUsableLocationMode() {
        val enabled = WidgetSettings(
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Fixed,
            fixedLocationQuery = "大阪市",
            rainAlertEnabled = true,
        )
        val noLocation = enabled.copy(weatherLocationMode = WeatherLocationMode.Disabled)

        assertTrue(isRainAlertConfigured(enabled))
        assertFalse(isRainAlertConfigured(noLocation))
    }
}
