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
private const val MINUTE_MILLIS: Long = 60_000L
private const val RAIN_FORECAST_PAST_GRACE_MILLIS: Long = 5 * MINUTE_MILLIS
private const val RAIN_ALERT_EXPIRY_GRACE_MILLIS: Long = 1_000L

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
    val expectedRainGeneration: Long,
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
    currentRainGeneration: Long,
    currentLatitude: Double?,
    currentLongitude: Double?,
    currentLocationAtMillis: Long,
    guard: RainAlertWriteGuard,
): Boolean {
    if (currentRainGeneration != guard.expectedRainGeneration) return false
    if (!guard.tracksCurrentLocation) return true

    val capturedAt = guard.expectedCurrentLocationAtMillis!!
    if (currentLocationAtMillis > capturedAt) return false
    if (currentLocationAtMillis < capturedAt) return true
    return currentLatitude == guard.expectedCurrentLatitude &&
        currentLongitude == guard.expectedCurrentLongitude
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

        val minutes = ((observation.timestampMillis - baseTimestamp) / MINUTE_MILLIS).toInt()
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

    val level = levelForMinutes(candidate.minutesUntilRain)
    return RainAlertState(
        level = level,
        minutesUntilRain = candidate.minutesUntilRain,
        rainAtMillis = candidate.rainAtMillis,
        rainfallMmPerHour = candidate.rainfallMmPerHour,
        nearbyOnly = candidate.nearbyOnly,
        updatedAtMillis = evaluatedAtMillis,
    )
}

internal fun levelForMinutes(minutesUntilRain: Int): RainAlertLevel = when {
    minutesUntilRain <= 15 -> RainAlertLevel.Imminent
    minutesUntilRain <= 30 -> RainAlertLevel.Soon
    else -> RainAlertLevel.Watch
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
    return ceil((rainAt - nowMillis) / MINUTE_MILLIS.toDouble()).toInt()
}

internal fun effectiveRainAlertLevel(
    storedLevel: RainAlertLevel,
    rainAtMillis: Long?,
    fallbackMinutesUntilRain: Int?,
    nowMillis: Long,
): RainAlertLevel {
    if (storedLevel == RainAlertLevel.None || storedLevel == RainAlertLevel.Raining) return storedLevel
    val minutes = remainingRainMinutes(rainAtMillis, nowMillis) ?: fallbackMinutesUntilRain
    return minutes?.let(::levelForMinutes) ?: storedLevel
}

internal fun isEffectiveRainAlertFresh(
    storedLevel: RainAlertLevel,
    updatedAtMillis: Long,
    rainAtMillis: Long?,
    fallbackMinutesUntilRain: Int?,
    nowMillis: Long,
): Boolean {
    if (storedLevel == RainAlertLevel.Raining) {
        val observedAtMillis = minOf(rainAtMillis ?: updatedAtMillis, updatedAtMillis)
        return isRainAlertFresh(
            level = RainAlertLevel.Raining,
            updatedAtMillis = observedAtMillis,
            nowMillis = nowMillis,
        )
    }
    if (
        rainAtMillis != null &&
        nowMillis > rainAtMillis + RAIN_FORECAST_PAST_GRACE_MILLIS
    ) {
        return false
    }
    val effectiveLevel = effectiveRainAlertLevel(
        storedLevel = storedLevel,
        rainAtMillis = rainAtMillis,
        fallbackMinutesUntilRain = fallbackMinutesUntilRain,
        nowMillis = nowMillis,
    )
    return isRainAlertFresh(effectiveLevel, updatedAtMillis, nowMillis)
}

internal fun rainAlertExpiryAtMillis(alert: RainAlertState): Long? {
    if (!alert.isActive || alert.updatedAtMillis <= 0L) return null
    val updatedAt = alert.updatedAtMillis
    if (alert.level == RainAlertLevel.Raining) {
        val observedAtMillis = minOf(alert.rainAtMillis ?: updatedAt, updatedAt)
        return observedAtMillis + RAINING_ALERT_MAX_AGE_MILLIS + RAIN_ALERT_EXPIRY_GRACE_MILLIS
    }

    val rainAt = alert.rainAtMillis ?: run {
        val maxAge = rainAlertMaxAgeMillis(alert.level)
        return if (maxAge > 0L) updatedAt + maxAge + RAIN_ALERT_EXPIRY_GRACE_MILLIS else null
    }
    val levelAtUpdate = effectiveRainAlertLevel(
        storedLevel = alert.level,
        rainAtMillis = rainAt,
        fallbackMinutesUntilRain = alert.minutesUntilRain,
        nowMillis = updatedAt,
    )
    val soonStart = maxOf(updatedAt, rainAt - 30L * MINUTE_MILLIS)
    val imminentStart = maxOf(updatedAt, rainAt - 15L * MINUTE_MILLIS)
    val candidates = mutableListOf<Long>()

    if (levelAtUpdate == RainAlertLevel.Watch) {
        val watchExpiry = updatedAt + RAIN_ALERT_MAX_AGE_MILLIS + RAIN_ALERT_EXPIRY_GRACE_MILLIS
        if (watchExpiry < soonStart) candidates += watchExpiry
    }
    if (levelAtUpdate == RainAlertLevel.Watch || levelAtUpdate == RainAlertLevel.Soon) {
        val soonExpiry = maxOf(
            soonStart,
            updatedAt + SOON_ALERT_MAX_AGE_MILLIS + RAIN_ALERT_EXPIRY_GRACE_MILLIS,
        )
        if (soonExpiry < imminentStart) candidates += soonExpiry
    }
    candidates += maxOf(
        imminentStart,
        updatedAt + IMMINENT_ALERT_MAX_AGE_MILLIS + RAIN_ALERT_EXPIRY_GRACE_MILLIS,
    )

    val urgencyExpiry = candidates.minOrNull()
    val forecastExpiry = rainAt + RAIN_FORECAST_PAST_GRACE_MILLIS + RAIN_ALERT_EXPIRY_GRACE_MILLIS
    return if (urgencyExpiry == null) forecastExpiry else minOf(urgencyExpiry, forecastExpiry)
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
