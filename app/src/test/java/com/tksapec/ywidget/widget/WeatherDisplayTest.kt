package com.tksapec.ywidget.widget

import com.tksapec.ywidget.data.WeatherLocationMode
import com.tksapec.ywidget.data.WidgetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherDisplayTest {
    @Test
    fun disabledWeatherHasNoDisplay() {
        assertNull(weatherDisplay(WidgetSettings(), now = 10_000L))
    }

    @Test
    fun activeWeatherRefreshShowsUpdating() {
        val settings = WidgetSettings(
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Current,
            weatherRefreshing = true,
            refreshStartedAtMillis = 1_000L,
        )

        val display = weatherDisplay(settings, now = 2_000L)!!

        assertEquals("天気更新中...", display.text)
        assertFalse(display.isWarning)
    }

    @Test
    fun firstWeatherFailureShowsErrorOnly() {
        val settings = WidgetSettings(
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Current,
            lastWeatherError = "現在地を取得できませんでした",
        )

        val display = weatherDisplay(settings, now = 10_000L)!!

        assertEquals("現在地を取得できませんでした", display.text)
        assertTrue(display.isWarning)
    }

    @Test
    fun failedRefreshKeepsLastSuccessfulWeather() {
        val settings = WidgetSettings(
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Fixed,
            locationLabel = "東京",
            weatherCode = 1,
            temperatureCelsius = 24.8,
            lastWeatherError = "天気取得失敗",
        )

        val display = weatherDisplay(settings, now = 10_000L)!!

        assertEquals("東京 ⛅ 24℃ / 更新失敗", display.text)
        assertTrue(display.isWarning)
    }

    @Test
    fun activeRefreshKeepsLastSuccessfulWeather() {
        val settings = WidgetSettings(
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Fixed,
            locationLabel = "東京",
            weatherCode = 1,
            temperatureCelsius = 24.8,
            weatherRefreshing = true,
            refreshStartedAtMillis = 1_000L,
        )

        val display = weatherDisplay(settings, now = 2_000L)!!

        assertEquals("東京 ⛅ 24℃ / 更新中", display.text)
        assertFalse(display.isWarning)
    }

    @Test
    fun completedRefreshFlagDoesNotOverrideWeatherData() {
        val settings = WidgetSettings(
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Fixed,
            locationLabel = "東京",
            weatherCode = 0,
            temperatureCelsius = 25.2,
            weatherRefreshing = true,
            refreshStartedAtMillis = 1_000L,
            lastRefreshFinishedAtMillis = 2_000L,
            lastRefreshResult = com.tksapec.ywidget.data.RefreshResult.Success,
        )

        assertEquals("東京 ☀ 25℃", weatherDisplay(settings, now = 3_000L)?.text)
    }

    @Test
    fun successfulWeatherUsesNormalDisplay() {
        val settings = WidgetSettings(
            weatherEnabled = true,
            weatherLocationMode = WeatherLocationMode.Fixed,
            locationLabel = "東京",
            weatherCode = 0,
            temperatureCelsius = 25.2,
        )

        val display = weatherDisplay(settings, now = 10_000L)!!

        assertEquals("東京 ☀ 25℃", display.text)
        assertFalse(display.isWarning)
    }
}
