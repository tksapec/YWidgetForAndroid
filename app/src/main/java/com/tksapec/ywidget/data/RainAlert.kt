package com.tksapec.ywidget.data

import kotlin.math.cos
import kotlin.math.sqrt

internal const val RAIN_CENTER_PROBE_ID: String = "center"
internal const val RAIN_ALERT_THRESHOLD_MM_PER_HOUR: Double = 0.5
internal const val RAIN_ALERT_RADIUS_KM: Double = 3.0
internal const val RAIN_CENTER_FORECAST_HORIZON_MINUTES: Int = 60
internal const val RAIN_NEARBY_FORECAST_HORIZON_MINUTES: Int = 30
internal const val RAIN_ALERT_MAX_AGE_MILLIS: Long = 45 * 60 * 1_000L

enum class RainAlertLevel {
    None,
    Watch,
    Soon,
    Imminent,
    Raining,
    ;

    companion object {
        fun fromName(name: String?): RainAlertLevel = entries.firstOrNull { it.name == name } ?: None
    }
}

internal enum class RainObservationType {
    Observation,
    Forecast,
}

internal data class RainProbePoint(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val isCenter: Boolean = false,
)

internal data class RainObservation(
    val probeId: String,
    val type: RainObservationType,
    val timestampMillis: Long,
    val rainfallMmPerHour: Double,
)

internal data class RainAlertState(
    val level: RainAlertLevel,
    val minutesUntilRain: Int? = null,
    val rainfallMmPerHour: Double? = null,
    val nearbyOnly: Boolean = false,
    val updatedAtMillis: Long,
) {
    val isActive: Boolean
        get() = level != RainAlertLevel.None
}

internal fun buildRainProbePoints(
    latitude: Double,
    longitude: Double,
    radiusKm: Double = RAIN_ALERT_RADIUS_KM,
): List<RainProbePoint> {
    val safeRadiusKm = radiusKm.coerceAtLeast(0.0)
    val latitudeRadians = Math.toRadians(latitude)
    val kilometersPerDegreeLatitude = 111.32
    val kilometersPerDegreeLongitude = (111.32 * cos(latitudeRadians)).coerceAtLeast(0.01)
    val northSouthDelta = safeRadiusKm / kilometersPerDegreeLatitude
    val eastWestDelta = safeRadiusKm / kilometersPerDegreeLongitude
    val diagonalScale = 1.0 / sqrt(2.0)

    fun point(id: String, northFactor: Double, eastFactor: Double): RainProbePoint = RainProbePoint(
        id = id,
        latitude = (latitude + northSouthDelta * northFactor).coerceIn(-90.0, 90.0),
        longitude = normalizeLongitude(longitude + eastWestDelta * eastFactor),
    )

    return listOf(
        RainProbePoint(RAIN_CENTER_PROBE_ID, latitude, normalizeLongitude(longitude), isCenter = true),
        point("n", 1.0, 0.0),
        point("ne", diagonalScale, diagonalScale),
        point("e", 0.0, 1.0),
        point("se", -diagonalScale, diagonalScale),
        point("s", -1.0, 0.0),
        point("sw", -diagonalScale, -diagonalScale),
        point("w", 0.0, -1.0),
        point("nw", diagonalScale, -diagonalScale),
    )
}

internal fun evaluateRainAlert(
    observations: List<RainObservation>,
    evaluatedAtMillis: Long,
): RainAlertState {
    val baseTimestamp = observations
        .filter { it.probeId == RAIN_CENTER_PROBE_ID && it.type == RainObservationType.Observation }
        .maxOfOrNull { it.timestampMillis }
        ?: observations.minOfOrNull { it.timestampMillis }
        ?: return RainAlertState(RainAlertLevel.None, updatedAtMillis = evaluatedAtMillis)

    val currentCenter = observations
        .filter {
            it.probeId == RAIN_CENTER_PROBE_ID &&
                it.type == RainObservationType.Observation &&
                it.timestampMillis == baseTimestamp
        }
        .maxByOrNull { it.rainfallMmPerHour }

    if (currentCenter != null && currentCenter.rainfallMmPerHour >= RAIN_ALERT_THRESHOLD_MM_PER_HOUR) {
        return RainAlertState(
            level = RainAlertLevel.Raining,
            minutesUntilRain = 0,
            rainfallMmPerHour = currentCenter.rainfallMmPerHour,
            nearbyOnly = false,
            updatedAtMillis = evaluatedAtMillis,
        )
    }

    val candidates = observations.mapNotNull { observation ->
        if (observation.rainfallMmPerHour < RAIN_ALERT_THRESHOLD_MM_PER_HOUR) return@mapNotNull null
        if (observation.probeId == RAIN_CENTER_PROBE_ID && observation.type == RainObservationType.Observation) {
            return@mapNotNull null
        }

        val minutes = ((observation.timestampMillis - baseTimestamp) / 60_000L).toInt()
        if (minutes < 0) return@mapNotNull null

        val nearbyOnly = observation.probeId != RAIN_CENTER_PROBE_ID
        val horizon = if (nearbyOnly) {
            RAIN_NEARBY_FORECAST_HORIZON_MINUTES
        } else {
            RAIN_CENTER_FORECAST_HORIZON_MINUTES
        }
        if (minutes > horizon) return@mapNotNull null

        RainCandidate(
            minutesUntilRain = minutes,
            rainfallMmPerHour = observation.rainfallMmPerHour,
            nearbyOnly = nearbyOnly,
        )
    }

    val candidate = candidates.minWithOrNull(
        compareBy<RainCandidate> { it.minutesUntilRain }
            .thenBy { it.nearbyOnly },
    ) ?: return RainAlertState(RainAlertLevel.None, updatedAtMillis = evaluatedAtMillis)

    val level = when {
        candidate.minutesUntilRain <= 15 -> RainAlertLevel.Imminent
        candidate.minutesUntilRain <= 30 -> RainAlertLevel.Soon
        else -> RainAlertLevel.Watch
    }
    return RainAlertState(
        level = level,
        minutesUntilRain = candidate.minutesUntilRain,
        rainfallMmPerHour = candidate.rainfallMmPerHour,
        nearbyOnly = candidate.nearbyOnly,
        updatedAtMillis = evaluatedAtMillis,
    )
}

internal fun isRainAlertFresh(updatedAtMillis: Long, nowMillis: Long): Boolean {
    return isTimestampFresh(
        timestampMillis = updatedAtMillis,
        nowMillis = nowMillis,
        maxAgeMillis = RAIN_ALERT_MAX_AGE_MILLIS,
    )
}

private data class RainCandidate(
    val minutesUntilRain: Int,
    val rainfallMmPerHour: Double,
    val nearbyOnly: Boolean,
)

private fun normalizeLongitude(longitude: Double): Double {
    var normalized = longitude
    while (normalized > 180.0) normalized -= 360.0
    while (normalized < -180.0) normalized += 360.0
    return normalized
}
