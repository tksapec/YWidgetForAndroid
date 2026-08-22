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
    fun changingWeatherModeClearsMismatchedWeatherCache() = withPreferences { preferences ->
        preferences.updateWeatherLocationMode(WeatherLocationMode.Fixed)
        preferences.saveWeather(
            code = 1,
            temperatureCelsius = 24.0,
            locationLabel = "東京",
            updatedAtMillis = 1_000L,
        )

        preferences.updateWeatherLocationMode(WeatherLocationMode.Current)

        val settings = preferences.currentSettings()
        assertNull(settings.weatherCode)
        assertNull(settings.temperatureCelsius)
        assertNull(settings.locationLabel)
        assertEquals(0L, settings.weatherUpdatedAtMillis)
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
