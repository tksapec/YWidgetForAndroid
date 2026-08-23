package com.tksapec.ywidget.network

import com.tksapec.ywidget.data.RAIN_CENTER_PROBE_ID
import com.tksapec.ywidget.data.RainProbePoint
import org.junit.Test

class YahooRainClientValidationTest {
    private val points = listOf(
        RainProbePoint(RAIN_CENTER_PROBE_ID, latitude = 35.0, longitude = 135.0, isCenter = true),
        RainProbePoint("w", latitude = 35.0, longitude = 134.97),
    )

    @Test(expected = YahooRainParseException::class)
    fun rejectsFeatureFarFromEveryRequestedProbe() {
        YahooRainClient("client-id").parseRainObservations(
            response(
                feature("130.000000,30.000000", observation = true),
                feature("134.970000,35.000000", observation = false),
            ),
            points,
        )
    }

    @Test(expected = YahooRainParseException::class)
    fun rejectsResponseWithoutCenterObservation() {
        YahooRainClient("client-id").parseRainObservations(
            response(feature("134.970000,35.000000", observation = true)),
            points,
        )
    }

    @Test(expected = YahooRainParseException::class)
    fun rejectsDuplicateFeaturesForSameProbe() {
        YahooRainClient("client-id").parseRainObservations(
            response(
                feature("135.000000,35.000000", observation = true),
                feature("135.000001,35.000001", observation = false),
            ),
            points,
        )
    }

    private fun response(vararg features: String): String =
        "{\"Feature\":[${features.joinToString(",")}] }"

    private fun feature(coordinates: String, observation: Boolean): String {
        val type = if (observation) "observation" else "forecast"
        return """
            {
              "Geometry":{"Coordinates":"$coordinates"},
              "Property":{"WeatherList":{"Weather":[
                {"Type":"$type","Date":"202608230900","Rainfall":0.5}
              ]}}
            }
        """.trimIndent()
    }
}
