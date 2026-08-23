package com.tksapec.ywidget.network

import com.tksapec.ywidget.data.RAIN_CENTER_PROBE_ID
import com.tksapec.ywidget.data.RainObservationType
import com.tksapec.ywidget.data.RainProbePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YahooRainClientTest {
    private val points = listOf(
        RainProbePoint(RAIN_CENTER_PROBE_ID, latitude = 35.0, longitude = 135.0, isCenter = true),
        RainProbePoint("w", latitude = 35.0, longitude = 134.97),
    )

    @Test
    fun parsesMultipleFeaturesAndMapsCoordinatesBackToProbeIds() {
        val observations = YahooRainClient("client-id").parseRainObservations(SAMPLE_JSON, points)

        assertEquals(4, observations.size)
        assertEquals(2, observations.count { it.probeId == RAIN_CENTER_PROBE_ID })
        assertEquals(2, observations.count { it.probeId == "w" })

        val center = observations.filter { it.probeId == RAIN_CENTER_PROBE_ID }.sortedBy { it.timestampMillis }
        assertEquals(RainObservationType.Observation, center[0].type)
        assertEquals(0.0, center[0].rainfallMmPerHour, 0.0001)
        assertEquals(RainObservationType.Forecast, center[1].type)
        assertEquals(1.25, center[1].rainfallMmPerHour, 0.0001)
        assertEquals(10 * 60_000L, center[1].timestampMillis - center[0].timestampMillis)
    }

    @Test(expected = YahooRainParseException::class)
    fun rejectsResponseWithoutFeatureArray() {
        YahooRainClient("client-id").parseRainObservations("{\"ResultInfo\":{\"Status\":200}}", points)
    }

    @Test(expected = YahooRainParseException::class)
    fun rejectsMalformedJson() {
        YahooRainClient("client-id").parseRainObservations("not-json", points)
    }

    @Test
    fun requestUrlContainsAllRequiredParametersAndCenterFirstCoordinates() {
        val url = YahooRainClient("client id").buildRequestUrl(points)

        assertTrue(url.startsWith("https://map.yahooapis.jp/weather/V1/place?"))
        assertTrue(url.contains("appid=client+id"))
        assertTrue(url.contains("output=json"))
        assertTrue(url.contains("interval=5"))
        assertTrue(url.contains("past=0"))
        assertTrue(url.contains("coordinates=135.0,35.0%20134.97,35.0"))
    }

    companion object {
        private const val SAMPLE_JSON = """
            {
              "ResultInfo": {"Count":2,"Total":2,"Start":1,"Status":200},
              "Feature": [
                {
                  "Geometry": {"Type":"point","Coordinates":"135.000000,35.000000"},
                  "Property": {
                    "WeatherList": {
                      "Weather": [
                        {"Type":"observation","Date":"202608230900","Rainfall":0},
                        {"Type":"forecast","Date":"202608230910","Rainfall":1.25}
                      ]
                    }
                  }
                },
                {
                  "Geometry": {"Type":"point","Coordinates":"134.970000,35.000000"},
                  "Property": {
                    "WeatherList": {
                      "Weather": [
                        {"Type":"observation","Date":"202608230900","Rainfall":0.8},
                        {"Type":"forecast","Date":"202608230910","Rainfall":2.5}
                      ]
                    }
                  }
                }
              ]
            }
        """
    }
}
