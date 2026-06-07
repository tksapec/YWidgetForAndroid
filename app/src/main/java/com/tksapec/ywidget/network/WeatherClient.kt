package com.tksapec.ywidget.network

import com.tksapec.ywidget.data.WEATHER_CODE_ERROR_MESSAGE
import com.tksapec.ywidget.data.WEATHER_DATA_FORMAT_ERROR_MESSAGE
import com.tksapec.ywidget.data.WEATHER_TEMPERATURE_ERROR_MESSAGE
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CurrentWeather(
    val code: Int,
    val temperatureCelsius: Double,
)

class WeatherClient {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetch(latitude: Double, longitude: Double): CurrentWeather {
        val encodedLatitude = URLEncoder.encode(latitude.toString(), "UTF-8")
        val encodedLongitude = URLEncoder.encode(longitude.toString(), "UTF-8")
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$encodedLatitude" +
            "&longitude=$encodedLongitude" +
            "&current=temperature_2m,weather_code" +
            "&timezone=auto"

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "YWidget/1.0")

        return try {
            if (connection.responseCode !in 200..299) {
                error("Weather request failed: HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseCurrentWeather(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseCurrentWeather(body: String): CurrentWeather {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { error(WEATHER_DATA_FORMAT_ERROR_MESSAGE) }
        val current = root["current"]
            ?.let { element -> runCatching { element.jsonObject }.getOrNull() }
            ?: error(WEATHER_DATA_FORMAT_ERROR_MESSAGE)
        val code = current["weather_code"]
            ?.let { element -> runCatching { element.jsonPrimitive.intOrNull }.getOrNull() }
            ?: error(WEATHER_CODE_ERROR_MESSAGE)
        val temperature = current["temperature_2m"]
            ?.let { element -> runCatching { element.jsonPrimitive.doubleOrNull }.getOrNull() }
            ?: error(WEATHER_TEMPERATURE_ERROR_MESSAGE)
        return CurrentWeather(
            code = code,
            temperatureCelsius = temperature,
        )
    }
}
