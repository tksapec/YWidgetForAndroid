package com.tksapec.ywidget.network

import com.tksapec.ywidget.data.WEATHER_CODE_ERROR_MESSAGE
import com.tksapec.ywidget.data.WEATHER_DATA_FORMAT_ERROR_MESSAGE
import com.tksapec.ywidget.data.WEATHER_TEMPERATURE_ERROR_MESSAGE
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherClientTest {
    @Test
    fun parseCurrentWeatherRequiresCurrentObject() {
        val error = runCatching {
            WeatherClient().parseCurrentWeather("""{}""")
        }.exceptionOrNull()

        assertEquals(WEATHER_DATA_FORMAT_ERROR_MESSAGE, error?.message)
    }

    @Test
    fun parseCurrentWeatherRequiresWeatherCode() {
        val error = runCatching {
            WeatherClient().parseCurrentWeather("""{"current":{"temperature_2m":24.5}}""")
        }.exceptionOrNull()

        assertEquals(WEATHER_CODE_ERROR_MESSAGE, error?.message)
    }

    @Test
    fun parseCurrentWeatherRequiresTemperature() {
        val error = runCatching {
            WeatherClient().parseCurrentWeather("""{"current":{"weather_code":1}}""")
        }.exceptionOrNull()

        assertEquals(WEATHER_TEMPERATURE_ERROR_MESSAGE, error?.message)
    }

    @Test
    fun parseCurrentWeatherReturnsWeather() {
        val weather = WeatherClient().parseCurrentWeather(
            """{"current":{"weather_code":2,"temperature_2m":21.5}}""",
        )

        assertEquals(2, weather.code)
        assertEquals(21.5, weather.temperatureCelsius, 0.001)
    }
}
