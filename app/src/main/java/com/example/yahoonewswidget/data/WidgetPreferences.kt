package com.example.yahoonewswidget.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

val Context.widgetDataStore by preferencesDataStore(name = "widget_settings")

class WidgetPreferences(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val settingsFlow: Flow<WidgetSettings> = context.widgetDataStore.data.map { preferences ->
        val category = NewsCategory.fromName(preferences[Keys.category] ?: NewsCategory.Top.name)
        val newsJson = preferences[Keys.newsJson].orEmpty()
        val news = runCatching {
            if (newsJson.isBlank()) emptyList() else json.decodeFromString<List<NewsItem>>(newsJson)
        }.getOrDefault(emptyList())

        WidgetSettings(
            category = category,
            displayCount = (preferences[Keys.displayCount] ?: 4).coerceIn(3, 8),
            updateIntervalMinutes = preferences[Keys.updateIntervalMinutes] ?: 60L,
            news = news,
            newsUpdatedAtMillis = preferences[Keys.newsUpdatedAtMillis] ?: 0L,
            weatherEnabled = preferences[Keys.weatherEnabled] ?: true,
            weatherCode = preferences[Keys.weatherCode],
            temperatureCelsius = preferences[Keys.temperatureCelsius],
            weatherUpdatedAtMillis = preferences[Keys.weatherUpdatedAtMillis] ?: 0L,
        )
    }

    suspend fun currentSettings(): WidgetSettings = settingsFlow.first()

    suspend fun updateCategory(category: NewsCategory) {
        context.widgetDataStore.edit { it[Keys.category] = category.name }
    }

    suspend fun updateDisplayCount(count: Int) {
        context.widgetDataStore.edit { it[Keys.displayCount] = count.coerceIn(3, 8) }
    }

    suspend fun updateInterval(minutes: Long) {
        context.widgetDataStore.edit { it[Keys.updateIntervalMinutes] = minutes }
    }

    suspend fun updateWeatherEnabled(enabled: Boolean) {
        context.widgetDataStore.edit { it[Keys.weatherEnabled] = enabled }
    }

    suspend fun saveNews(news: List<NewsItem>, updatedAtMillis: Long) {
        context.widgetDataStore.edit {
            it[Keys.newsJson] = json.encodeToString(news)
            it[Keys.newsUpdatedAtMillis] = updatedAtMillis
        }
    }

    suspend fun saveWeather(code: Int, temperatureCelsius: Double, updatedAtMillis: Long) {
        context.widgetDataStore.edit {
            it[Keys.weatherCode] = code
            it[Keys.temperatureCelsius] = temperatureCelsius
            it[Keys.weatherUpdatedAtMillis] = updatedAtMillis
        }
    }

    private object Keys {
        val category = stringPreferencesKey("category")
        val displayCount = intPreferencesKey("display_count")
        val updateIntervalMinutes = longPreferencesKey("update_interval_minutes")
        val newsJson = stringPreferencesKey("news_json")
        val newsUpdatedAtMillis = longPreferencesKey("news_updated_at_millis")
        val weatherEnabled = booleanPreferencesKey("weather_enabled")
        val weatherCode = intPreferencesKey("weather_code")
        val temperatureCelsius = doublePreferencesKey("temperature_celsius")
        val weatherUpdatedAtMillis = longPreferencesKey("weather_updated_at_millis")
    }
}
