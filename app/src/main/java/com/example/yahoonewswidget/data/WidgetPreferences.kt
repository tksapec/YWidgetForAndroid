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
        val selectedCategories = decodeCategories(
            preferences[Keys.selectedCategories],
            category,
        )
        val newsJson = preferences[Keys.newsJson].orEmpty()
        val news = runCatching {
            if (newsJson.isBlank()) emptyList() else json.decodeFromString<List<NewsItem>>(newsJson)
        }.getOrDefault(emptyList())
        val launcherAppsJson = preferences[Keys.launcherAppsJson].orEmpty()
        val launcherApps = runCatching {
            if (launcherAppsJson.isBlank()) {
                emptyList()
            } else {
                json.decodeFromString<List<LauncherAppShortcut>>(launcherAppsJson)
            }
        }.getOrDefault(emptyList())

        WidgetSettings(
            category = category,
            selectedCategories = selectedCategories,
            displayCount = (preferences[Keys.displayCount] ?: 4).coerceIn(3, 8),
            displayStyle = DisplayStyle.fromName(preferences[Keys.displayStyle] ?: DisplayStyle.Standard.name),
            updateIntervalMinutes = preferences[Keys.updateIntervalMinutes] ?: 60L,
            news = news,
            newsUpdatedAtMillis = preferences[Keys.newsUpdatedAtMillis] ?: 0L,
            weatherEnabled = preferences[Keys.weatherEnabled] ?: true,
            weatherLocationMode = WeatherLocationMode.fromName(
                preferences[Keys.weatherLocationMode] ?: WeatherLocationMode.Current.name,
            ),
            locationLabel = preferences[Keys.locationLabel],
            fixedLocationQuery = preferences[Keys.fixedLocationQuery].orEmpty(),
            fixedLatitude = preferences[Keys.fixedLatitude],
            fixedLongitude = preferences[Keys.fixedLongitude],
            weatherCode = preferences[Keys.weatherCode],
            temperatureCelsius = preferences[Keys.temperatureCelsius],
            weatherUpdatedAtMillis = preferences[Keys.weatherUpdatedAtMillis] ?: 0L,
            lastNewsError = preferences[Keys.lastNewsError],
            lastWeatherError = preferences[Keys.lastWeatherError],
            launcherApps = normalizeLauncherApps(launcherApps),
        )
    }

    suspend fun currentSettings(): WidgetSettings = settingsFlow.first()

    suspend fun updateCategory(category: NewsCategory) {
        context.widgetDataStore.edit { it[Keys.category] = category.name }
    }

    suspend fun updateSelectedCategories(categories: Set<NewsCategory>) {
        val safeCategories = orderedCategories(categories).ifEmpty { listOf(NewsCategory.Top) }
        context.widgetDataStore.edit {
            it[Keys.selectedCategories] = safeCategories.joinToString(",") { category -> category.name }
            it[Keys.category] = safeCategories.first().name
        }
    }

    suspend fun updateDisplayCount(count: Int) {
        context.widgetDataStore.edit { it[Keys.displayCount] = count.coerceIn(3, 8) }
    }

    suspend fun updateDisplayStyle(style: DisplayStyle) {
        context.widgetDataStore.edit { it[Keys.displayStyle] = style.name }
    }

    suspend fun updateInterval(minutes: Long) {
        context.widgetDataStore.edit { it[Keys.updateIntervalMinutes] = minutes }
    }

    suspend fun updateWeatherEnabled(enabled: Boolean) {
        context.widgetDataStore.edit { it[Keys.weatherEnabled] = enabled }
    }

    suspend fun updateWeatherLocationMode(mode: WeatherLocationMode) {
        context.widgetDataStore.edit {
            it[Keys.weatherLocationMode] = mode.name
            it[Keys.weatherEnabled] = mode != WeatherLocationMode.Disabled
            if (mode == WeatherLocationMode.Disabled) {
                it.remove(Keys.lastWeatherError)
            }
        }
    }

    suspend fun updateFixedLocationQuery(query: String) {
        context.widgetDataStore.edit {
            it[Keys.fixedLocationQuery] = query.trim()
            it.remove(Keys.fixedLatitude)
            it.remove(Keys.fixedLongitude)
        }
    }

    suspend fun saveFixedLocation(query: String, latitude: Double, longitude: Double, label: String) {
        context.widgetDataStore.edit {
            it[Keys.fixedLocationQuery] = query.trim()
            it[Keys.fixedLatitude] = latitude
            it[Keys.fixedLongitude] = longitude
            it[Keys.locationLabel] = label
            it[Keys.weatherLocationMode] = WeatherLocationMode.Fixed.name
            it[Keys.weatherEnabled] = true
            it.remove(Keys.lastWeatherError)
        }
    }

    suspend fun saveNews(news: List<NewsItem>, updatedAtMillis: Long) {
        context.widgetDataStore.edit {
            it[Keys.newsJson] = json.encodeToString(news)
            it[Keys.newsUpdatedAtMillis] = updatedAtMillis
            it.remove(Keys.lastNewsError)
        }
    }

    suspend fun saveNewsError(message: String) {
        context.widgetDataStore.edit { it[Keys.lastNewsError] = message }
    }

    suspend fun saveWeather(
        code: Int,
        temperatureCelsius: Double,
        locationLabel: String?,
        updatedAtMillis: Long,
    ) {
        context.widgetDataStore.edit {
            it[Keys.weatherCode] = code
            it[Keys.temperatureCelsius] = temperatureCelsius
            locationLabel?.let { label -> it[Keys.locationLabel] = label }
            it[Keys.weatherUpdatedAtMillis] = updatedAtMillis
            it.remove(Keys.lastWeatherError)
        }
    }

    suspend fun saveWeatherError(message: String) {
        context.widgetDataStore.edit { it[Keys.lastWeatherError] = message }
    }

    suspend fun updateLauncherApps(apps: List<LauncherAppShortcut>) {
        context.widgetDataStore.edit {
            it[Keys.launcherAppsJson] = json.encodeToString(normalizeLauncherApps(apps))
        }
    }

    private fun decodeCategories(value: String?, fallback: NewsCategory): Set<NewsCategory> {
        val categories = value
            ?.split(",")
            ?.mapNotNull { name -> NewsCategory.entries.firstOrNull { it.name == name } }
            ?.toSet()
            .orEmpty()
        return orderedCategories(categories).ifEmpty { listOf(fallback) }.toSet()
    }

    private fun orderedCategories(categories: Set<NewsCategory>): List<NewsCategory> {
        return NewsCategory.entries.filter { it in categories }
    }

    private fun normalizeLauncherApps(apps: List<LauncherAppShortcut>): List<LauncherAppShortcut> {
        return apps
            .filter { it.displayName.isNotBlank() && it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
            .take(3)
    }

    private object Keys {
        val category = stringPreferencesKey("category")
        val selectedCategories = stringPreferencesKey("selected_categories")
        val displayCount = intPreferencesKey("display_count")
        val displayStyle = stringPreferencesKey("display_style")
        val updateIntervalMinutes = longPreferencesKey("update_interval_minutes")
        val newsJson = stringPreferencesKey("news_json")
        val newsUpdatedAtMillis = longPreferencesKey("news_updated_at_millis")
        val weatherEnabled = booleanPreferencesKey("weather_enabled")
        val weatherLocationMode = stringPreferencesKey("weather_location_mode")
        val locationLabel = stringPreferencesKey("location_label")
        val fixedLocationQuery = stringPreferencesKey("fixed_location_query")
        val fixedLatitude = doublePreferencesKey("fixed_latitude")
        val fixedLongitude = doublePreferencesKey("fixed_longitude")
        val weatherCode = intPreferencesKey("weather_code")
        val temperatureCelsius = doublePreferencesKey("temperature_celsius")
        val weatherUpdatedAtMillis = longPreferencesKey("weather_updated_at_millis")
        val lastNewsError = stringPreferencesKey("last_news_error")
        val lastWeatherError = stringPreferencesKey("last_weather_error")
        val launcherAppsJson = stringPreferencesKey("launcher_apps_json")
    }
}
