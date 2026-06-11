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
import org.junit.Test

class WidgetPreferencesTest {
    @Test
    fun finishRefreshClearsAllActiveFlagsAndStoresCompletion() = runBlocking {
        val directory = Files.createTempDirectory("ywidget-preferences-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
                directory.resolve("widget.preferences_pb")
            }
            val preferences = WidgetPreferences(dataStore)
            preferences.markRefreshRunning(startedAtMillis = 1_000L)
            preferences.updateWeatherRefreshing(true)
            preferences.updateRefreshQueued(true)

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
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }
}
