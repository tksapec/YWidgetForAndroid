package com.tksapec.ywidget.network

import com.tksapec.ywidget.data.RainObservation
import com.tksapec.ywidget.data.RainObservationType
import com.tksapec.ywidget.data.RainProbePoint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

open class YahooRainException(message: String, cause: Throwable? = null) : IOException(message, cause)

class YahooRainHttpException(val responseCode: Int) :
    YahooRainException("Yahoo rain request failed: HTTP $responseCode")

class YahooRainParseException(message: String, cause: Throwable? = null) :
    YahooRainException(message, cause)

internal class YahooRainClient(private val clientId: String) {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetch(points: List<RainProbePoint>): List<RainObservation> {
        val url = buildRequestUrl(points)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "YWidgetForAndroid/1.1")

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw YahooRainHttpException(responseCode)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseRainObservations(body, points)
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildRequestUrl(points: List<RainProbePoint>): String {
        require(clientId.isNotBlank()) { "Yahoo Client ID is not configured" }
        require(points.isNotEmpty()) { "At least one rain probe point is required" }
        require(points.size <= 10) { "Yahoo Weather API supports at most 10 coordinates" }

        val encodedClientId = URLEncoder.encode(clientId.trim(), Charsets.UTF_8.name())
        val coordinates = points.joinToString("%20") { point ->
            "${point.longitude},${point.latitude}"
        }
        return "$BASE_URL?appid=$encodedClientId" +
            "&coordinates=$coordinates" +
            "&output=json" +
            "&interval=5" +
            "&past=0"
    }

    internal fun parseRainObservations(
        body: String,
        points: List<RainProbePoint>,
    ): List<RainObservation> {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (error: Exception) {
            throw YahooRainParseException("Yahoo rain response is not valid JSON", error)
        }
        val features = try {
            root["Feature"]?.jsonArray
        } catch (error: Exception) {
            throw YahooRainParseException("Yahoo rain response has invalid Feature data", error)
        } ?: throw YahooRainParseException("Yahoo rain response has no Feature data")

        val observations = features.flatMap { featureElement ->
            val feature = try {
                featureElement.jsonObject
            } catch (error: Exception) {
                throw YahooRainParseException("Yahoo rain Feature is invalid", error)
            }
            val coordinateText = try {
                feature["Geometry"]
                    ?.jsonObject
                    ?.get("Coordinates")
                    ?.jsonPrimitive
                    ?.contentOrNull
            } catch (_: Exception) {
                null
            } ?: throw YahooRainParseException("Yahoo rain Feature has no coordinates")
            val (longitude, latitude) = parseCoordinates(coordinateText)
            val probe = nearestProbe(latitude, longitude, points)
                ?: throw YahooRainParseException("Yahoo rain response does not match a requested coordinate")
            val weather = try {
                feature["Property"]
                    ?.jsonObject
                    ?.get("WeatherList")
                    ?.jsonObject
                    ?.get("Weather")
                    ?.jsonArray
            } catch (error: Exception) {
                throw YahooRainParseException("Yahoo rain WeatherList is invalid", error)
            } ?: throw YahooRainParseException("Yahoo rain Feature has no WeatherList")

            weather.mapNotNull { weatherElement ->
                val item = try {
                    weatherElement.jsonObject
                } catch (error: Exception) {
                    throw YahooRainParseException("Yahoo rain Weather item is invalid", error)
                }
                val type = when (item["Type"]?.jsonPrimitive?.contentOrNull) {
                    "observation" -> RainObservationType.Observation
                    "forecast" -> RainObservationType.Forecast
                    else -> return@mapNotNull null
                }
                val date = item["Date"]?.jsonPrimitive?.contentOrNull
                    ?: throw YahooRainParseException("Yahoo rain Weather item has no Date")
                val rainfall = try {
                    item["Rainfall"]?.jsonPrimitive?.doubleOrNull
                } catch (_: Exception) {
                    null
                } ?: throw YahooRainParseException("Yahoo rain Weather item has invalid Rainfall")
                RainObservation(
                    probeId = probe.id,
                    type = type,
                    timestampMillis = parseYahooDate(date),
                    rainfallMmPerHour = rainfall,
                )
            }
        }

        if (observations.isEmpty()) {
            throw YahooRainParseException("Yahoo rain response contains no rainfall observations")
        }
        return observations
    }

    private fun parseCoordinates(value: String): Pair<Double, Double> {
        val parts = value.split(",")
        if (parts.size != 2) throw YahooRainParseException("Yahoo rain coordinates are invalid")
        val longitude = parts[0].trim().toDoubleOrNull()
            ?: throw YahooRainParseException("Yahoo rain longitude is invalid")
        val latitude = parts[1].trim().toDoubleOrNull()
            ?: throw YahooRainParseException("Yahoo rain latitude is invalid")
        return longitude to latitude
    }

    private fun nearestProbe(
        latitude: Double,
        longitude: Double,
        points: List<RainProbePoint>,
    ): RainProbePoint? {
        return points.minByOrNull { point ->
            val latitudeDelta = point.latitude - latitude
            val longitudeDelta = point.longitude - longitude
            latitudeDelta * latitudeDelta + longitudeDelta * longitudeDelta
        }
    }

    private fun parseYahooDate(value: String): Long {
        if (value.length != 12) throw YahooRainParseException("Yahoo rain Date is invalid")
        val parser = SimpleDateFormat("yyyyMMddHHmm", Locale.US).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        }
        return try {
            parser.parse(value)?.time ?: throw YahooRainParseException("Yahoo rain Date is invalid")
        } catch (error: YahooRainParseException) {
            throw error
        } catch (error: Exception) {
            throw YahooRainParseException("Yahoo rain Date is invalid", error)
        }
    }

    companion object {
        private const val BASE_URL = "https://map.yahooapis.jp/weather/V1/place"
    }
}
