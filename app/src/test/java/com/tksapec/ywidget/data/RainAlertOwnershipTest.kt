package com.tksapec.ywidget.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainAlertOwnershipTest {
    @Test
    fun fixedLocationResultIsRejectedAfterRainGenerationChanges() {
        val guard = RainAlertWriteGuard(expectedRainGeneration = 4L)

        assertFalse(
            rainAlertWriteGuardMatches(
                currentRainGeneration = 5L,
                currentLatitude = null,
                currentLongitude = null,
                currentLocationAtMillis = 0L,
                guard = guard,
            ),
        )
    }

    @Test
    fun currentLocationResultIsRejectedAfterLocationChanges() {
        val guard = RainAlertWriteGuard(
            expectedRainGeneration = 4L,
            expectedCurrentLatitude = 34.6937,
            expectedCurrentLongitude = 135.5023,
            expectedCurrentLocationAtMillis = 1_000L,
        )

        assertFalse(
            rainAlertWriteGuardMatches(
                currentRainGeneration = 4L,
                currentLatitude = 34.7000,
                currentLongitude = 135.5100,
                currentLocationAtMillis = 2_000L,
                guard = guard,
            ),
        )
    }

    @Test
    fun currentLocationResultIsAcceptedOnlyForExactCapturedLocation() {
        val guard = RainAlertWriteGuard(
            expectedRainGeneration = 4L,
            expectedCurrentLatitude = 34.6937,
            expectedCurrentLongitude = 135.5023,
            expectedCurrentLocationAtMillis = 1_000L,
        )

        assertTrue(
            rainAlertWriteGuardMatches(
                currentRainGeneration = 4L,
                currentLatitude = 34.6937,
                currentLongitude = 135.5023,
                currentLocationAtMillis = 1_000L,
                guard = guard,
            ),
        )
    }

    @Test
    fun fixedLocationGuardDoesNotDependOnCurrentLocationCache() {
        val guard = RainAlertWriteGuard(expectedRainGeneration = 4L)

        assertTrue(
            rainAlertWriteGuardMatches(
                currentRainGeneration = 4L,
                currentLatitude = 35.0,
                currentLongitude = 136.0,
                currentLocationAtMillis = 99_000L,
                guard = guard,
            ),
        )
    }
}
