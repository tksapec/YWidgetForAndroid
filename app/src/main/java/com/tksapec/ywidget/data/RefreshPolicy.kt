package com.tksapec.ywidget.data

import java.net.URI

internal const val CURRENT_LOCATION_CACHE_MAX_AGE_MILLIS: Long = 30 * 60 * 1_000L
internal const val REFRESH_GENERATION_INPUT_KEY: String = "refresh_generation"
internal const val MAX_REFRESH_ATTEMPTS: Int = 3

internal fun refreshGenerationMatches(currentGeneration: Long, expectedGeneration: Long): Boolean {
    return currentGeneration == expectedGeneration
}

internal fun resolveWorkerRefreshGeneration(
    inputGeneration: Long?,
    currentGeneration: Long,
): Long? {
    if (inputGeneration != null) return inputGeneration
    return 0L.takeIf { currentGeneration == 0L }
}

internal fun isTimestampFresh(timestampMillis: Long, nowMillis: Long, maxAgeMillis: Long): Boolean {
    if (timestampMillis <= 0L || maxAgeMillis < 0L) return false
    if (timestampMillis > nowMillis) return false
    return nowMillis - timestampMillis <= maxAgeMillis
}

internal fun freshLocationTimestampOrNull(
    locationTimestampMillis: Long,
    nowMillis: Long,
    maxAgeMillis: Long = CURRENT_LOCATION_CACHE_MAX_AGE_MILLIS,
): Long? {
    return locationTimestampMillis.takeIf {
        isTimestampFresh(it, nowMillis, maxAgeMillis)
    }
}

internal fun isRetryableHttpStatus(statusCode: Int): Boolean {
    return statusCode == 408 || statusCode == 429 || statusCode in 500..599
}

internal fun shouldRetryTransientFailure(
    retryNeeded: Boolean,
    runAttemptCount: Int,
    maxAttempts: Int = MAX_REFRESH_ATTEMPTS,
): Boolean {
    if (!retryNeeded || maxAttempts <= 1) return false
    return runAttemptCount < maxAttempts - 1
}

internal fun isAllowedExternalUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return false
    }
    return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}
