package com.example.yahoonewswidget.network

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
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
        connection.setRequestProperty("User-Agent", "YahooNewsWidget/1.0")

        return try {
            if (connection.responseCode !in 200..299) {
                error("Weather request failed: HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val current = json.parseToJsonElement(body).jsonObject["current"]!!.jsonObject
            CurrentWeather(
                code = current["weather_code"]!!.jsonPrimitive.int,
                temperatureCelsius = current["temperature_2m"]!!.jsonPrimitive.double,
            )
        } finally {
            connection.disconnect()
        }
    }
}
