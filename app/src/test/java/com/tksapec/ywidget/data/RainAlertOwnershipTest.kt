package com.tksapec.ywidget.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RainAlertOwnershipTest {
    @Test
    fun fixedLocationResultIsRejectedAfterRefreshGenerationChanges() {
        val guard = RainAlertWriteGuard(expectedRefreshGeneration = 4L)

        assertFalse(
            rainAlertWriteGuardMatches(
                currentRefreshGeneration = 5L,
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
            expectedRefreshGeneration = 4L,
            expectedCurrentLatitude = 34.6937,
            expectedCurrentLongitude = 135.5023,
            expectedCurrentLocationAtMillis = 1_000L,
        )

        assertFalse(
            rainAlertWriteGuardMatches(
                currentRefreshGeneration = 4L,
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
            expectedRefreshGeneration = 4L,
            expectedCurrentLatitude = 34.6937,
            expectedCurrentLongitude = 135.5023,
            expectedCurrentLocationAtMillis = 1_000L,
        )

        assertTrue(
            rainAlertWriteGuardMatches(
                currentRefreshGeneration = 4L,
                currentLatitude = 34.6937,
                currentLongitude = 135.5023,
                currentLocationAtMillis = 1_000L,
                guard = guard,
            ),
        )
    }

    @Test
    fun fixedLocationGuardDoesNotDependOnCurrentLocationCache() {
        val guard = RainAlertWriteGuard(expectedRefreshGeneration = 4L)

        assertTrue(
            rainAlertWriteGuardMatches(
                currentRefreshGeneration = 4L,
                currentLatitude = 35.0,
                currentLongitude = 136.0,
                currentLocationAtMillis = 99_000L,
                guard = guard,
            ),
        )
    }
}
