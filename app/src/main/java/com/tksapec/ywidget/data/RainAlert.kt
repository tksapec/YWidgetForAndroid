package com.tksapec.ywidget.data

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sqrt

internal const val RAIN_CENTER_PROBE_ID: String = "center"
internal const val RAIN_ALERT_THRESHOLD_MM_PER_HOUR: Double = 0.5
internal const val RAIN_ALERT_RADIUS_KM: Double = 3.0
internal const val RAIN_CENTER_FORECAST_HORIZON_MINUTES: Int = 60
internal const val RAIN_NEARBY_FORECAST_HORIZON_MINUTES: Int = 30
internal const val RAIN_ALERT_MAX_AGE_MILLIS: Long = 45 * 60 * 1_000L
internal const val RAINING_ALERT_MAX_AGE_MILLIS: Long = 15 * 60 * 1_000L
internal const val IMMINENT_ALERT_MAX_AGE_MILLIS: Long = 20 * 60 * 1_000L
internal const val SOON_ALERT_MAX_AGE_MILLIS: Long = 30 * 60 * 1_000L
internal const val HEAVY_RAIN_THRESHOLD_MM_PER_HOUR: Double = 10.0
internal const val MODERATE_RAIN_THRESHOLD_MM_PER_HOUR: Double = 2.0

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

data class RainAlertState(
    val level: RainAlertLevel,
    val minutesUntilRain: Int? = null,
    val rainAtMillis: Long? = null,
    val rainfallMmPerHour: Double? = null,
    val nearbyOnly: Boolean = false,
    val updatedAtMillis: Long,
) {
    val isActive: Boolean
        get() = level != RainAlertLevel.None
}

internal data class RainAlertWriteGuard(
    val expectedRefreshGeneration: Long,
    val expectedCurrentLatitude: Double? = null,
    val expectedCurrentLongitude: Double? = null,
    val expectedCurrentLocationAtMillis: Long? = null,
) {
    val tracksCurrentLocation: Boolean
        get() = expectedCurrentLatitude != null &&
            expectedCurrentLongitude != null &&
            expectedCurrentLocationAtMillis != null
}

internal fun rainAlertWriteGuardMatches(
    currentRefreshGeneration: Long,
    currentLatitude: Double?,
    currentLongitude: Double?,
    currentLocationAtMillis: Long,
    guard: RainAlertWriteGuard,
): Boolean {
    if (currentRefreshGeneration != guard.expectedRefreshGeneration) return false
    if (!guard.tracksCurrentLocation) return true
    return currentLatitude == guard.expectedCurrentLatitude &&
        currentLongitude == guard.expectedCurrentLongitude &&
        currentLocationAtMillis == guard.expectedCurrentLocationAtMillis
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
            rainAtMillis = baseTimestamp,
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
            rainAtMillis = observation.timestampMillis,
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
        rainAtMillis = candidate.rainAtMillis,
        rainfallMmPerHour = candidate.rainfallMmPerHour,
        nearbyOnly = candidate.nearbyOnly,
        updatedAtMillis = evaluatedAtMillis,
    )
}

internal fun rainAlertMaxAgeMillis(level: RainAlertLevel): Long = when (level) {
    RainAlertLevel.Raining -> RAINING_ALERT_MAX_AGE_MILLIS
    RainAlertLevel.Imminent -> IMMINENT_ALERT_MAX_AGE_MILLIS
    RainAlertLevel.Soon -> SOON_ALERT_MAX_AGE_MILLIS
    RainAlertLevel.Watch -> RAIN_ALERT_MAX_AGE_MILLIS
    RainAlertLevel.None -> 0L
}

internal fun isRainAlertFresh(
    level: RainAlertLevel,
    updatedAtMillis: Long,
    nowMillis: Long,
): Boolean {
    val maxAgeMillis = rainAlertMaxAgeMillis(level)
    return maxAgeMillis > 0L && isTimestampFresh(
        timestampMillis = updatedAtMillis,
        nowMillis = nowMillis,
        maxAgeMillis = maxAgeMillis,
    )
}

internal fun remainingRainMinutes(rainAtMillis: Long?, nowMillis: Long): Int? {
    val rainAt = rainAtMillis ?: return null
    if (rainAt <= nowMillis) return 0
    return ceil((rainAt - nowMillis) / 60_000.0).toInt()
}

internal fun rainIntensityLabel(rainfallMmPerHour: Double?): String? = when {
    rainfallMmPerHour == null -> null
    rainfallMmPerHour >= HEAVY_RAIN_THRESHOLD_MM_PER_HOUR -> "強い雨"
    rainfallMmPerHour >= MODERATE_RAIN_THRESHOLD_MM_PER_HOUR -> "やや強い雨"
    else -> null
}

private data class RainCandidate(
    val minutesUntilRain: Int,
    val rainAtMillis: Long,
    val rainfallMmPerHour: Double,
    val nearbyOnly: Boolean,
)

private fun normalizeLongitude(longitude: Double): Double {
    var normalized = longitude
    while (normalized > 180.0) normalized -= 360.0
    while (normalized < -180.0) normalized += 360.0
    return normalized
}
