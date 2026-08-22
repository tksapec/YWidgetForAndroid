package com.tksapec.ywidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPolicyTest {
    @Test
    fun refreshGenerationMustMatchOwner() {
        assertTrue(refreshGenerationMatches(3L, 3L))
        assertFalse(refreshGenerationMatches(4L, 3L))
    }

    @Test
    fun timestampFreshnessRejectsOldFutureAndMissingValues() {
        val now = 2_000_000L

        assertTrue(isTimestampFresh(now - 1_000L, now, 60_000L))
        assertFalse(isTimestampFresh(now - 60_001L, now, 60_000L))
        assertFalse(isTimestampFresh(now + 1L, now, 60_000L))
        assertFalse(isTimestampFresh(0L, now, 60_000L))
    }

    @Test
    fun freshLocationTimestampKeepsActualMeasurementTime() {
        assertEquals(
            1_950_000L,
            freshLocationTimestampOrNull(
                locationTimestampMillis = 1_950_000L,
                nowMillis = 2_000_000L,
                maxAgeMillis = 60_000L,
            ),
        )
        assertNull(
            freshLocationTimestampOrNull(
                locationTimestampMillis = 1_900_000L,
                nowMillis = 2_000_000L,
                maxAgeMillis = 60_000L,
            ),
        )
    }

    @Test
    fun retryableHttpStatusOnlyIncludesTransientResponses() {
        assertTrue(isRetryableHttpStatus(408))
        assertTrue(isRetryableHttpStatus(429))
        assertTrue(isRetryableHttpStatus(500))
        assertTrue(isRetryableHttpStatus(599))
        assertFalse(isRetryableHttpStatus(400))
        assertFalse(isRetryableHttpStatus(404))
    }

    @Test
    fun externalUrlRequiresHttpsAndHost() {
        assertTrue(isAllowedExternalUrl("https://news.yahoo.co.jp/a"))
        assertFalse(isAllowedExternalUrl("http://news.yahoo.co.jp/a"))
        assertFalse(isAllowedExternalUrl("javascript:alert(1)"))
        assertFalse(isAllowedExternalUrl("https:///missing-host"))
    }
}
