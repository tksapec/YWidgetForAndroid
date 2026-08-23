package com.tksapec.ywidget.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetPreferencesTest {
    @Test
    fun finishRefreshClearsAllActiveFlagsAndStoresCompletion() = withPreferences { preferences ->
        val generation = preferences.updateRefreshQueued(true)
        assertTrue(preferences.markRefreshRunning(generation, startedAtMillis = 1_000L))
        preferences.updateWeatherRefreshing(true, generation)

        preferences.finishRefresh(
            result = RefreshResult.Success,
            message = "更新完了",
            finishedAtMillis = 2_000L,
        )

        val settings = preferences.currentSettings()
        assertFalse(settings.newsRefreshing)
        assertFalse(settings.weatherRefreshing)
        assertFalse(settings.refreshQueued)
        assertEquals(0L, settings.refreshStartedAtMillis)
        assertEquals(2_000L, settings.lastRefreshFinishedAtMillis)
        assertEquals(RefreshResult.Success, settings.lastRefreshResult)
        assertNotNull(settings.lastRefreshMessage)
    }

    @Test
    fun oldWorkerCannotFinishNewQueuedGeneration() = withPreferences { preferences ->
        val oldGeneration = preferences.updateRefreshQueued(true)
        assertTrue(preferences.markRefreshRunning(oldGeneration, startedAtMillis = 1_000L))
        val newGeneration = preferences.updateRefreshQueued(true)

        val finished = preferences.finishRefreshIfGeneration(
            expectedGeneration = oldGeneration,
            result = RefreshResult.Cancelled,
            message = "old worker cancelled",
            finishedAtMillis = 2_000L,
        )

        val settings = preferences.currentSettings()
        assertFalse(finished)
        assertEquals(newGeneration, settings.refreshGeneration)
        assertTrue(settings.refreshQueued)
        assertNull(settings.lastRefreshResult)
    }

    @Test
    fun oldWorkerCannotSaveNewsIntoNewGeneration() = withPreferences { preferences ->
        val oldGeneration = preferences.updateRefreshQueued(true)
        assertTrue(preferences.markRefreshRunning(oldGeneration, startedAtMillis = 1_000L))
        val newGeneration = preferences.updateRefreshQueued(true)

        preferences.saveNews(
            news = listOf(NewsItem("old", "https://example.com/old")),
            updatedAtMillis = 2_000L,
            expectedGeneration = oldGeneration,
        )

        val settings = preferences.currentSettings()
        assertEquals(newGeneration, settings.refreshGeneration)
        assertTrue(settings.news.isEmpty())
        assertEquals(0L, settings.newsUpdatedAtMillis)
    }

    @Test
    fun resetInvalidatesQueuedAndRunningWorkers() = withPreferences { preferences ->
        val oldGeneration = preferences.updateRefreshQueued(true)
        assertTrue(preferences.markRefreshRunning(oldGeneration, startedAtMillis = 1_000L))

        preferences.clearRefreshState()

        val settings = preferences.currentSettings()
        assertTrue(settings.refreshGeneration != oldGeneration)
        assertFalse(settings.refreshQueued)
        assertFalse(settings.newsRefreshing)
        assertFalse(settings.weatherRefreshing)
        assertEquals(0L, settings.refreshStartedAtMillis)
        assertFalse(preferences.markRefreshRunning(oldGeneration, startedAtMillis = 2_000L))
    }

    @Test
    fun changingCategoriesClearsMismatchedNewsCache() = withPreferences { preferences ->
        preferences.saveNews(
            news = listOf(NewsItem("top", "https://example.com/top")),
            updatedAtMillis = 1_000L,
        )

        preferences.updateSelectedCategories(setOf(NewsCategory.Sports))

        val settings = preferences.currentSettings()
        assertEquals(setOf(NewsCategory.Sports), settings.selectedCategories)
        assertTrue(settings.news.isEmpty())
        assertEquals(0L, settings.newsUpdatedAtMillis)
    }

    @Test
    fun changingWeatherModeClearsMismatchedWeatherAndRainCache() = withPreferences { preferences ->
        preferences.updateWeatherLocationMode(WeatherLocationMode.Fixed)
        preferences.saveWeather(
            code = 1,
            temperatureCelsius = 24.0,
            locationLabel = "東京",
            updatedAtMillis = 1_000L,
        )
        preferences.saveRainAlert(
            RainAlertState(
                level = RainAlertLevel.Soon,
                minutesUntilRain = 20,
                rainAtMillis = 21 * 60_000L,
                rainfallMmPerHour = 1.2,
                updatedAtMillis = 1_000L,
            ),
        )

        preferences.updateWeatherLocationMode(WeatherLocationMode.Current)

        val settings = preferences.currentSettings()
        assertNull(settings.weatherCode)
        assertNull(settings.temperatureCelsius)
        assertNull(settings.locationLabel)
        assertEquals(0L, settings.weatherUpdatedAtMillis)
        assertEquals(RainAlertLevel.None, settings.rainAlertLevel)
        assertNull(settings.rainAlertRainAtMillis)
        assertEquals(0L, settings.rainAlertUpdatedAtMillis)
    }

    @Test
    fun nullWeatherLabelRemovesPreviousLocationLabel() = withPreferences { preferences ->
        preferences.saveWeather(
            code = 1,
            temperatureCelsius = 24.0,
            locationLabel = "東京",
            updatedAtMillis = 1_000L,
        )
        preferences.saveWeather(
            code = 2,
            temperatureCelsius = 25.0,
            locationLabel = null,
            updatedAtMillis = 2_000L,
        )

        assertNull(preferences.currentSettings().locationLabel)
    }

    @Test
    fun rainAlertDefaultsToOptOutAndUsesIndependentGeneration() = withPreferences { preferences ->
        val before = preferences.currentSettings()
        assertFalse(before.rainAlertEnabled)

        preferences.updateRainAlertEnabled(true)
        val enabled = preferences.currentSettings()
        assertTrue(enabled.rainAlertEnabled)
        assertEquals(before.refreshGeneration, enabled.refreshGeneration)
        assertTrue(enabled.rainAlertGeneration != before.rainAlertGeneration)

        val enabledRainGeneration = enabled.rainAlertGeneration
        preferences.updateRainAlertEnabled(false)
        val disabled = preferences.currentSettings()
        assertFalse(disabled.rainAlertEnabled)
        assertEquals(before.refreshGeneration, disabled.refreshGeneration)
        assertTrue(disabled.rainAlertGeneration != enabledRainGeneration)
    }

    @Test
    fun normalNewsRefreshDoesNotInvalidateRainGeneration() = withPreferences { preferences ->
        preferences.updateWeatherLocationMode(WeatherLocationMode.Fixed)
        preferences.updateRainAlertEnabled(true)
        val before = preferences.currentSettings()

        preferences.updateRefreshQueued(true)
        val after = preferences.currentSettings()

        assertTrue(after.refreshGeneration != before.refreshGeneration)
        assertEquals(before.rainAlertGeneration, after.rainAlertGeneration)
    }

    @Test
    fun saveRainAlertPersistsAbsoluteRainTimeAndClearsError() = withPreferences { preferences ->
        preferences.saveRainAlertError("temporary")

        preferences.saveRainAlert(
            RainAlertState(
                level = RainAlertLevel.Imminent,
                minutesUntilRain = 10,
                rainAtMillis = 605_000L,
                rainfallMmPerHour = 2.5,
                nearbyOnly = true,
                updatedAtMillis = 5_000L,
            ),
        )

        val settings = preferences.currentSettings()
        assertEquals(RainAlertLevel.Imminent, settings.rainAlertLevel)
        assertEquals(10, settings.rainAlertMinutesUntilRain)
        assertEquals(605_000L, settings.rainAlertRainAtMillis!!)
        assertEquals(2.5, settings.rainAlertRainfallMmPerHour!!, 0.0001)
        assertTrue(settings.rainAlertNearbyOnly)
        assertEquals(5_000L, settings.rainAlertUpdatedAtMillis)
        assertNull(settings.lastRainAlertError)
    }

    @Test
    fun fixedRainResultCannotSaveAfterRegionChanges() = withPreferences { preferences ->
        preferences.updateWeatherLocationMode(WeatherLocationMode.Fixed)
        preferences.updateFixedLocationQuery("大阪市")
        preferences.updateRainAlertEnabled(true)
        val oldRainGeneration = preferences.currentSettings().rainAlertGeneration
        val oldGuard = RainAlertWriteGuard(oldRainGeneration)

        preferences.updateFixedLocationQuery("神戸市")
        val saved = preferences.saveRainAlert(
            RainAlertState(
                level = RainAlertLevel.Soon,
                minutesUntilRain = 20,
                rainAtMillis = 1_200_000L,
                rainfallMmPerHour = 2.0,
                updatedAtMillis = 10_000L,
            ),
            oldGuard,
        )

        assertFalse(saved)
        assertEquals(RainAlertLevel.None, preferences.currentSettings().rainAlertLevel)
    }

    @Test
    fun currentRainResultCannotSaveAfterNewerLocationReplacesSnapshot() = withPreferences { preferences ->
        preferences.updateWeatherLocationMode(WeatherLocationMode.Current)
        preferences.updateRainAlertEnabled(true)
        val settings = preferences.currentSettings()
        val normalGeneration = settings.refreshGeneration
        val rainGeneration = settings.rainAlertGeneration
        preferences.saveCurrentLocation(
            latitude = 34.6937,
            longitude = 135.5023,
            label = "大阪",
            locationAtMillis = 1_000L,
            expectedGeneration = normalGeneration,
        )
        val guard = RainAlertWriteGuard(
            expectedRainGeneration = rainGeneration,
            expectedCurrentLatitude = 34.6937,
            expectedCurrentLongitude = 135.5023,
            expectedCurrentLocationAtMillis = 1_000L,
        )

        preferences.saveCurrentLocation(
            latitude = 34.6900,
            longitude = 135.5100,
            label = "大阪",
            locationAtMillis = 2_000L,
            expectedGeneration = normalGeneration,
        )
        val saved = preferences.saveRainAlert(
            RainAlertState(
                level = RainAlertLevel.Imminent,
                minutesUntilRain = 10,
                rainAtMillis = 600_000L,
                rainfallMmPerHour = 1.5,
                updatedAtMillis = 3_000L,
            ),
            guard,
        )

        assertFalse(saved)
        assertEquals(RainAlertLevel.None, preferences.currentSettings().rainAlertLevel)
    }

    @Test
    fun rainLocationSnapshotPreservesExistingReverseGeocodeLabel() = withPreferences { preferences ->
        preferences.updateWeatherLocationMode(WeatherLocationMode.Current)
        preferences.updateRainAlertEnabled(true)
        val settings = preferences.currentSettings()
        preferences.saveCurrentLocation(
            latitude = 34.6937,
            longitude = 135.5023,
            label = "大阪市",
            locationAtMillis = 1_000L,
            expectedGeneration = settings.refreshGeneration,
        )

        val guard = preferences.saveRainCurrentLocationIfGeneration(
            latitude = 34.6940,
            longitude = 135.5030,
            locationAtMillis = 2_000L,
            expectedGeneration = settings.rainAlertGeneration,
        )

        assertNotNull(guard)
        val after = preferences.currentSettings()
        assertEquals("大阪市", after.lastCurrentLocationLabel)
        assertEquals(34.6940, after.lastCurrentLatitude!!, 0.0001)
        assertEquals(2_000L, after.lastCurrentLocationAtMillis)
    }

    @Test
    fun rainGuardRejectsWritesAfterOptOut() = withPreferences { preferences ->
        preferences.updateWeatherLocationMode(WeatherLocationMode.Fixed)
        preferences.updateRainAlertEnabled(true)
        val guard = RainAlertWriteGuard(preferences.currentSettings().rainAlertGeneration)

        preferences.updateRainAlertEnabled(false)
        val saved = preferences.saveRainAlert(
            RainAlertState(
                level = RainAlertLevel.Raining,
                minutesUntilRain = 0,
                rainAtMillis = 5_000L,
                rainfallMmPerHour = 3.0,
                updatedAtMillis = 5_000L,
            ),
            guard,
        )

        assertFalse(saved)
        assertEquals(RainAlertLevel.None, preferences.currentSettings().rainAlertLevel)
    }

    @Test
    fun saveRainAlertErrorKeepsFreshAlertForTemporaryFailure() = withPreferences { preferences ->
        preferences.saveRainAlert(
            RainAlertState(
                level = RainAlertLevel.Watch,
                minutesUntilRain = 50,
                rainAtMillis = 3_005_000L,
                rainfallMmPerHour = 0.8,
                updatedAtMillis = 5_000L,
            ),
        )

        preferences.saveRainAlertError("network")

        val settings = preferences.currentSettings()
        assertEquals(RainAlertLevel.Watch, settings.rainAlertLevel)
        assertEquals(5_000L, settings.rainAlertUpdatedAtMillis)
        assertEquals("network", settings.lastRainAlertError)
    }

    @Test
    fun clearRainAlertRemovesWarningButCanStoreDiagnostic() = withPreferences { preferences ->
        preferences.saveRainAlert(
            RainAlertState(
                level = RainAlertLevel.Raining,
                minutesUntilRain = 0,
                rainAtMillis = 5_000L,
                rainfallMmPerHour = 3.0,
                updatedAtMillis = 5_000L,
            ),
        )

        preferences.clearRainAlert("location unavailable")

        val settings = preferences.currentSettings()
        assertEquals(RainAlertLevel.None, settings.rainAlertLevel)
        assertNull(settings.rainAlertMinutesUntilRain)
        assertNull(settings.rainAlertRainAtMillis)
        assertNull(settings.rainAlertRainfallMmPerHour)
        assertFalse(settings.rainAlertNearbyOnly)
        assertEquals(0L, settings.rainAlertUpdatedAtMillis)
        assertEquals("location unavailable", settings.lastRainAlertError)
    }

    private fun withPreferences(block: suspend (WidgetPreferences) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("ywidget-preferences-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                directory.resolve("widget.preferences_pb")
            }
            block(WidgetPreferences(dataStore))
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
