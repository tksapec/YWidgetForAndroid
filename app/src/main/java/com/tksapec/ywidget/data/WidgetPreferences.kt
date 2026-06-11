package com.tksapec.ywidget.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

class WidgetPreferences internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.widgetDataStore)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val settingsFlow: Flow<WidgetSettings> = dataStore.data.map { preferences ->
        val category = NewsCategory.fromName(preferences[Keys.category] ?: NewsCategory.Top.name)
        val selectedCategories = decodeCategories(
            preferences[Keys.selectedCategories],
            category,
        )
        val newsJson = preferences[Keys.newsJson].orEmpty()
        val news = runCatching {
            if (newsJson.isBlank()) emptyList() else json.decodeFromString<List<NewsItem>>(newsJson)
        }.getOrDefault(emptyList())
        val launcherAppSlots = decodeLauncherAppSlots(
            slotsJson = preferences[Keys.launcherAppSlotsJson],
            legacyAppsJson = preferences[Keys.launcherAppsJson],
        )

        WidgetSettings(
            category = category,
            selectedCategories = selectedCategories,
            displayCount = (preferences[Keys.displayCount] ?: 4).coerceIn(3, 8),
            displayStyle = DisplayStyle.fromName(preferences[Keys.displayStyle] ?: DisplayStyle.Standard.name),
            updateIntervalMinutes = preferences[Keys.updateIntervalMinutes] ?: 60L,
            news = news,
            newsUpdatedAtMillis = preferences[Keys.newsUpdatedAtMillis] ?: 0L,
            newsRefreshing = preferences[Keys.newsRefreshing] ?: false,
            refreshQueued = preferences[Keys.refreshQueued] ?: false,
            refreshStartedAtMillis = preferences[Keys.refreshStartedAtMillis] ?: 0L,
            weatherEnabled = preferences[Keys.weatherEnabled] ?: false,
            weatherLocationMode = WeatherLocationMode.fromName(
                preferences[Keys.weatherLocationMode] ?: WeatherLocationMode.Disabled.name,
            ),
            locationLabel = preferences[Keys.locationLabel],
            fixedLocationQuery = preferences[Keys.fixedLocationQuery].orEmpty(),
            fixedLatitude = preferences[Keys.fixedLatitude],
            fixedLongitude = preferences[Keys.fixedLongitude],
            weatherCode = preferences[Keys.weatherCode],
            temperatureCelsius = preferences[Keys.temperatureCelsius],
            weatherUpdatedAtMillis = preferences[Keys.weatherUpdatedAtMillis] ?: 0L,
            weatherRefreshing = preferences[Keys.weatherRefreshing] ?: false,
            lastNewsError = preferences[Keys.lastNewsError],
            lastWeatherError = preferences[Keys.lastWeatherError],
            lastRefreshStartedAtMillis = preferences[Keys.lastRefreshStartedAtMillis] ?: 0L,
            lastRefreshFinishedAtMillis = preferences[Keys.lastRefreshFinishedAtMillis] ?: 0L,
            lastRefreshResult = RefreshResult.fromName(preferences[Keys.lastRefreshResult]),
            lastRefreshMessage = preferences[Keys.lastRefreshMessage],
            lastWidgetUpdatedAtMillis = preferences[Keys.lastWidgetUpdatedAtMillis] ?: 0L,
            lastWidgetUpdateError = preferences[Keys.lastWidgetUpdateError],
            lastCurrentLatitude = preferences[Keys.lastCurrentLatitude],
            lastCurrentLongitude = preferences[Keys.lastCurrentLongitude],
            lastCurrentLocationLabel = preferences[Keys.lastCurrentLocationLabel],
            launcherAppSlots = launcherAppSlots,
        )
    }

    suspend fun currentSettings(): WidgetSettings = settingsFlow.first()

    suspend fun updateCategory(category: NewsCategory) {
        dataStore.edit { it[Keys.category] = category.name }
    }

    suspend fun updateSelectedCategories(categories: Set<NewsCategory>) {
        val safeCategories = orderedCategories(categories).ifEmpty { listOf(NewsCategory.Top) }
        dataStore.edit {
            it[Keys.selectedCategories] = safeCategories.joinToString(",") { category -> category.name }
            it[Keys.category] = safeCategories.first().name
        }
    }

    suspend fun updateDisplayCount(count: Int) {
        dataStore.edit { it[Keys.displayCount] = count.coerceIn(3, 8) }
    }

    suspend fun updateDisplayStyle(style: DisplayStyle) {
        dataStore.edit { it[Keys.displayStyle] = style.name }
    }

    suspend fun updateInterval(minutes: Long) {
        dataStore.edit { it[Keys.updateIntervalMinutes] = minutes }
    }

    suspend fun updateWeatherEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.weatherEnabled] = enabled }
    }

    suspend fun updateWeatherLocationMode(mode: WeatherLocationMode) {
        dataStore.edit {
            it[Keys.weatherLocationMode] = mode.name
            it[Keys.weatherEnabled] = mode != WeatherLocationMode.Disabled
            if (mode == WeatherLocationMode.Disabled) {
                it.remove(Keys.lastWeatherError)
            }
        }
    }

    suspend fun updateFixedLocationQuery(query: String) {
        dataStore.edit {
            it[Keys.fixedLocationQuery] = query.trim()
            it.remove(Keys.fixedLatitude)
            it.remove(Keys.fixedLongitude)
        }
    }

    suspend fun saveFixedLocation(query: String, latitude: Double, longitude: Double, label: String) {
        dataStore.edit {
            it[Keys.fixedLocationQuery] = query.trim()
            it[Keys.fixedLatitude] = latitude
            it[Keys.fixedLongitude] = longitude
            it[Keys.locationLabel] = label
            it[Keys.weatherLocationMode] = WeatherLocationMode.Fixed.name
            it[Keys.weatherEnabled] = true
            it.remove(Keys.lastWeatherError)
        }
    }

    suspend fun saveNews(news: List<NewsItem>, updatedAtMillis: Long, warningMessage: String? = null) {
        dataStore.edit {
            it[Keys.newsJson] = json.encodeToString(news)
            it[Keys.newsUpdatedAtMillis] = updatedAtMillis
            it[Keys.newsRefreshing] = false
            if (warningMessage == null) {
                it.remove(Keys.lastNewsError)
            } else {
                it[Keys.lastNewsError] = warningMessage
            }
        }
    }

    suspend fun saveNewsError(message: String) {
        dataStore.edit {
            it[Keys.lastNewsError] = message
            it[Keys.newsRefreshing] = false
        }
    }

    suspend fun updateNewsRefreshing(refreshing: Boolean) {
        dataStore.edit {
            it[Keys.newsRefreshing] = refreshing
            if (refreshing) {
                it[Keys.refreshQueued] = false
                it[Keys.refreshStartedAtMillis] = System.currentTimeMillis()
            }
        }
    }

    suspend fun updateRefreshQueued(queued: Boolean) {
        dataStore.edit {
            it[Keys.refreshQueued] = queued
            if (queued) {
                val now = System.currentTimeMillis()
                it[Keys.refreshStartedAtMillis] = now
                it[Keys.lastRefreshStartedAtMillis] = now
                it.remove(Keys.lastRefreshFinishedAtMillis)
                it.remove(Keys.lastRefreshResult)
                it[Keys.lastRefreshMessage] = "更新予約中"
            }
        }
    }

    suspend fun markRefreshRunning(startedAtMillis: Long = System.currentTimeMillis()) {
        dataStore.edit {
            it[Keys.refreshQueued] = false
            it[Keys.newsRefreshing] = true
            it[Keys.refreshStartedAtMillis] = startedAtMillis
            it[Keys.lastRefreshStartedAtMillis] = startedAtMillis
            it.remove(Keys.lastRefreshFinishedAtMillis)
            it.remove(Keys.lastRefreshResult)
            it[Keys.lastRefreshMessage] = "更新中"
        }
    }

    suspend fun finishRefresh(result: RefreshResult, message: String, finishedAtMillis: Long = System.currentTimeMillis()) {
        dataStore.edit {
            it[Keys.refreshQueued] = false
            it[Keys.newsRefreshing] = false
            it[Keys.weatherRefreshing] = false
            it[Keys.refreshStartedAtMillis] = 0L
            it[Keys.lastRefreshFinishedAtMillis] = finishedAtMillis
            it[Keys.lastRefreshResult] = result.name
            it[Keys.lastRefreshMessage] = message
        }
    }

    suspend fun markRefreshStale(message: String = "前回更新が中断されました") {
        finishRefresh(RefreshResult.Stale, message)
    }

    suspend fun saveWidgetUpdateSuccess(updatedAtMillis: Long = System.currentTimeMillis()) {
        dataStore.edit {
            it[Keys.lastWidgetUpdatedAtMillis] = updatedAtMillis
            it.remove(Keys.lastWidgetUpdateError)
        }
    }

    suspend fun saveWidgetUpdateError(message: String) {
        dataStore.edit { it[Keys.lastWidgetUpdateError] = message }
    }

    suspend fun saveCurrentLocation(latitude: Double, longitude: Double, label: String?) {
        dataStore.edit {
            it[Keys.lastCurrentLatitude] = latitude
            it[Keys.lastCurrentLongitude] = longitude
            if (label.isNullOrBlank()) {
                it.remove(Keys.lastCurrentLocationLabel)
            } else {
                it[Keys.lastCurrentLocationLabel] = label
            }
        }
    }

    suspend fun saveWeather(
        code: Int,
        temperatureCelsius: Double,
        locationLabel: String?,
        updatedAtMillis: Long,
    ) {
        dataStore.edit {
            it[Keys.weatherCode] = code
            it[Keys.temperatureCelsius] = temperatureCelsius
            locationLabel?.let { label -> it[Keys.locationLabel] = label }
            it[Keys.weatherUpdatedAtMillis] = updatedAtMillis
            it[Keys.weatherRefreshing] = false
            it.remove(Keys.lastWeatherError)
        }
    }

    suspend fun saveWeatherError(message: String) {
        dataStore.edit {
            it[Keys.lastWeatherError] = message
            it[Keys.weatherRefreshing] = false
        }
    }

    suspend fun updateWeatherRefreshing(refreshing: Boolean) {
        dataStore.edit {
            it[Keys.weatherRefreshing] = refreshing
            if (refreshing) {
                it[Keys.refreshQueued] = false
                it[Keys.refreshStartedAtMillis] = System.currentTimeMillis()
            }
        }
    }

    suspend fun clearRefreshState() {
        dataStore.edit {
            it[Keys.refreshQueued] = false
            it[Keys.newsRefreshing] = false
            it[Keys.weatherRefreshing] = false
            it[Keys.refreshStartedAtMillis] = 0L
        }
    }

    suspend fun updateLauncherAppSlots(slots: List<LauncherAppSlot>) {
        dataStore.edit {
            it[Keys.launcherAppSlotsJson] = json.encodeToString(normalizeLauncherAppSlots(slots))
            it.remove(Keys.launcherAppsJson)
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

    private fun decodeLauncherAppSlots(
        slotsJson: String?,
        legacyAppsJson: String?,
    ): List<LauncherAppSlot> {
        val slots = runCatching {
            if (slotsJson.isNullOrBlank()) {
                emptyList()
            } else {
                json.decodeFromString<List<LauncherAppSlot>>(slotsJson)
            }
        }.getOrDefault(emptyList())
        if (slots.isNotEmpty()) return normalizeLauncherAppSlots(slots)

        val legacyApps = runCatching {
            if (legacyAppsJson.isNullOrBlank()) {
                emptyList()
            } else {
                json.decodeFromString<List<LauncherAppShortcut>>(legacyAppsJson)
            }
        }.getOrDefault(emptyList())

        return normalizeLauncherAppSlots(
            legacyApps
                .filter { it.displayName.isNotBlank() && it.packageName.isNotBlank() }
                .distinctBy { it.packageName }
                .take(3)
                .mapIndexed { index, app -> LauncherAppSlot(slotIndex = index, app = app) },
        )
    }

    private object Keys {
        val category = stringPreferencesKey("category")
        val selectedCategories = stringPreferencesKey("selected_categories")
        val displayCount = intPreferencesKey("display_count")
        val displayStyle = stringPreferencesKey("display_style")
        val updateIntervalMinutes = longPreferencesKey("update_interval_minutes")
        val newsJson = stringPreferencesKey("news_json")
        val newsUpdatedAtMillis = longPreferencesKey("news_updated_at_millis")
        val newsRefreshing = booleanPreferencesKey("news_refreshing")
        val refreshQueued = booleanPreferencesKey("refresh_queued")
        val refreshStartedAtMillis = longPreferencesKey("refresh_started_at_millis")
        val weatherEnabled = booleanPreferencesKey("weather_enabled")
        val weatherLocationMode = stringPreferencesKey("weather_location_mode")
        val locationLabel = stringPreferencesKey("location_label")
        val fixedLocationQuery = stringPreferencesKey("fixed_location_query")
        val fixedLatitude = doublePreferencesKey("fixed_latitude")
        val fixedLongitude = doublePreferencesKey("fixed_longitude")
        val weatherCode = intPreferencesKey("weather_code")
        val temperatureCelsius = doublePreferencesKey("temperature_celsius")
        val weatherUpdatedAtMillis = longPreferencesKey("weather_updated_at_millis")
        val weatherRefreshing = booleanPreferencesKey("weather_refreshing")
        val lastNewsError = stringPreferencesKey("last_news_error")
        val lastWeatherError = stringPreferencesKey("last_weather_error")
        val lastRefreshStartedAtMillis = longPreferencesKey("last_refresh_started_at_millis")
        val lastRefreshFinishedAtMillis = longPreferencesKey("last_refresh_finished_at_millis")
        val lastRefreshResult = stringPreferencesKey("last_refresh_result")
        val lastRefreshMessage = stringPreferencesKey("last_refresh_message")
        val lastWidgetUpdatedAtMillis = longPreferencesKey("last_widget_updated_at_millis")
        val lastWidgetUpdateError = stringPreferencesKey("last_widget_update_error")
        val lastCurrentLatitude = doublePreferencesKey("last_current_latitude")
        val lastCurrentLongitude = doublePreferencesKey("last_current_longitude")
        val lastCurrentLocationLabel = stringPreferencesKey("last_current_location_label")
        val launcherAppSlotsJson = stringPreferencesKey("launcher_app_slots_json")
        val launcherAppsJson = stringPreferencesKey("launcher_apps_json")
    }
}
