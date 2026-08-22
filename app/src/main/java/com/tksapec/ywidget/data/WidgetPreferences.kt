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
            refreshGeneration = preferences[Keys.refreshGeneration] ?: 0L,
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
            lastCurrentLocationAtMillis = preferences[Keys.lastCurrentLocationAtMillis] ?: 0L,
            launcherAppSlots = launcherAppSlots,
        )
    }

    suspend fun currentSettings(): WidgetSettings = settingsFlow.first()

    suspend fun updateCategory(category: NewsCategory) {
        dataStore.edit { it[Keys.category] = category.name }
    }

    suspend fun updateSelectedCategories(categories: Set<NewsCategory>) {
        val safeCategories = orderedCategories(categories).ifEmpty { listOf(NewsCategory.Top) }
        val encoded = safeCategories.joinToString(",") { category -> category.name }
        dataStore.edit {
            val changed = it[Keys.selectedCategories] != encoded
            it[Keys.selectedCategories] = encoded
            it[Keys.category] = safeCategories.first().name
            if (changed) {
                invalidateRefreshGeneration(it)
                clearNewsCache(it)
            }
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
            val changed = it[Keys.weatherLocationMode] != mode.name
            it[Keys.weatherLocationMode] = mode.name
            it[Keys.weatherEnabled] = mode != WeatherLocationMode.Disabled
            if (changed) {
                invalidateRefreshGeneration(it)
                clearWeatherCache(it)
            }
            if (mode == WeatherLocationMode.Disabled) {
                it.remove(Keys.lastWeatherError)
            }
        }
    }

    suspend fun updateFixedLocationQuery(query: String) {
        val normalized = query.trim()
        dataStore.edit {
            val changed = it[Keys.fixedLocationQuery].orEmpty() != normalized
            it[Keys.fixedLocationQuery] = normalized
            it.remove(Keys.fixedLatitude)
            it.remove(Keys.fixedLongitude)
            if (changed) {
                invalidateRefreshGeneration(it)
                clearWeatherCache(it)
            }
        }
    }

    suspend fun saveFixedLocation(
        query: String,
        latitude: Double,
        longitude: Double,
        label: String,
        expectedGeneration: Long? = null,
    ) {
        dataStore.edit {
            if (!generationMatches(it, expectedGeneration)) return@edit
            it[Keys.fixedLocationQuery] = query.trim()
            it[Keys.fixedLatitude] = latitude
            it[Keys.fixedLongitude] = longitude
            it[Keys.locationLabel] = label
            it[Keys.weatherLocationMode] = WeatherLocationMode.Fixed.name
            it[Keys.weatherEnabled] = true
            it.remove(Keys.lastWeatherError)
        }
    }

    suspend fun saveNews(
        news: List<NewsItem>,
        updatedAtMillis: Long,
        warningMessage: String? = null,
        expectedGeneration: Long? = null,
    ) {
        dataStore.edit {
            if (!generationMatches(it, expectedGeneration)) return@edit
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

    suspend fun saveNewsError(message: String, expectedGeneration: Long? = null) {
        dataStore.edit {
            if (!generationMatches(it, expectedGeneration)) return@edit
            it[Keys.lastNewsError] = message
            it[Keys.newsRefreshing] = false
        }
    }

    suspend fun updateNewsRefreshing(refreshing: Boolean, expectedGeneration: Long? = null) {
        dataStore.edit {
            if (!generationMatches(it, expectedGeneration)) return@edit
            it[Keys.newsRefreshing] = refreshing
            if (refreshing) it[Keys.refreshQueued] = false
        }
    }

    suspend fun updateRefreshQueued(queued: Boolean): Long {
        var generation = 0L
        dataStore.edit {
            generation = it[Keys.refreshGeneration] ?: 0L
            it[Keys.refreshQueued] = queued
            if (queued) {
                generation = nextGeneration(generation)
                it[Keys.refreshGeneration] = generation
                it[Keys.newsRefreshing] = false
                it[Keys.weatherRefreshing] = false
                it[Keys.refreshStartedAtMillis] = 0L
                it.remove(Keys.lastRefreshFinishedAtMillis)
                it.remove(Keys.lastRefreshResult)
                it[Keys.lastRefreshMessage] = "更新予約中"
            }
        }
        return generation
    }

    suspend fun markRefreshRunning(
        expectedGeneration: Long,
        startedAtMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        var started = false
        dataStore.edit {
            if (!generationMatches(it, expectedGeneration)) return@edit
            started = true
            it[Keys.refreshQueued] = false
            it[Keys.newsRefreshing] = true
            it[Keys.weatherRefreshing] = false
            it[Keys.refreshStartedAtMillis] = startedAtMillis
            it[Keys.lastRefreshStartedAtMillis] = startedAtMillis
            it.remove(Keys.lastRefreshFinishedAtMillis)
            it.remove(Keys.lastRefreshResult)
            it[Keys.lastRefreshMessage] = "更新中"
        }
        return started
    }

    suspend fun finishRefresh(
        result: RefreshResult,
        message: String,
        finishedAtMillis: Long = System.currentTimeMillis(),
    ) {
        dataStore.edit { finishRefreshEdit(it, result, message, finishedAtMillis) }
    }

    suspend fun finishRefreshIfGeneration(
        expectedGeneration: Long,
        result: RefreshResult,
        message: String,
        finishedAtMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        var finished = false
        dataStore.edit {
            if (!generationMatches(it, expectedGeneration)) return@edit
            finished = true
            finishRefreshEdit(it, result, message, finishedAtMillis)
        }
        return finished
    }

    suspend fun markRefreshStale(message: String = "前回更新が中断されました") {
        finishRefresh(RefreshResult.Stale, message)
    }

    suspend fun markRefreshStaleIfGeneration(
        expectedGeneration: Long,
        message: String = "前回更新が中断されました",
    ): Boolean {
        return finishRefreshIfGeneration(expectedGeneration, RefreshResult.Stale, message)
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

    suspend fun saveCurrentLocation(
        latitude: Double,
        longitude: Double,
        label: String?,
        locationAtMillis: Long = System.currentTimeMillis(),
        expectedGeneration: Long? = null,
    ) {
        dataStore.edit {
            if (!generationMatches(it, expectedGeneration)) return@edit
            it[Keys.lastCurrentLatitude] = latitude
            it[Keys.lastCurrentLongitude] = longitude
            it[Keys.lastCurrentLocationAtMillis] = locationAtMillis
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
        expectedGeneration: Long? = null,
    ) {
        dataStore.edit {
            if (!generationMatches(it, expectedGeneration)) return@edit
            it[Keys.weatherCode] = code
            it[Keys.temperatureCelsius] = temperatureCelsius
            if (locationLabel.isNullOrBlank()) {
                it.remove(Keys.locationLabel)
            } else {
                it[Keys.locationLabel] = locationLabel
            }
            it[Keys.weatherUpdatedAtMillis] = updatedAtMillis
            it[Keys.weatherRefreshing] = false
            it.remove(Keys.lastWeatherError)
        }
    }

    suspend fun saveWeatherError(message: String, expectedGeneration: Long? = null) {
        dataStore.edit {
            if (!generationMatches(it, expectedGeneration)) return@edit
            it[Keys.lastWeatherError] = message
            it[Keys.weatherRefreshing] = false
        }
    }

    suspend fun updateWeatherRefreshing(refreshing: Boolean, expectedGeneration: Long? = null) {
        dataStore.edit {
            if (!generationMatches(it, expectedGeneration)) return@edit
            it[Keys.weatherRefreshing] = refreshing
            if (refreshing) it[Keys.refreshQueued] = false
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

    private fun generationMatches(preferences: Preferences, expectedGeneration: Long?): Boolean {
        if (expectedGeneration == null) return true
        return refreshGenerationMatches(
            currentGeneration = preferences[Keys.refreshGeneration] ?: 0L,
            expectedGeneration = expectedGeneration,
        )
    }

    private fun invalidateRefreshGeneration(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        val next = nextGeneration(preferences[Keys.refreshGeneration] ?: 0L)
        preferences[Keys.refreshGeneration] = next
        preferences[Keys.refreshQueued] = false
        preferences[Keys.newsRefreshing] = false
        preferences[Keys.weatherRefreshing] = false
        preferences[Keys.refreshStartedAtMillis] = 0L
    }

    private fun clearNewsCache(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        preferences.remove(Keys.newsJson)
        preferences.remove(Keys.newsUpdatedAtMillis)
        preferences.remove(Keys.lastNewsError)
    }

    private fun clearWeatherCache(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        preferences.remove(Keys.weatherCode)
        preferences.remove(Keys.temperatureCelsius)
        preferences.remove(Keys.weatherUpdatedAtMillis)
        preferences.remove(Keys.locationLabel)
        preferences.remove(Keys.lastWeatherError)
    }

    private fun finishRefreshEdit(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        result: RefreshResult,
        message: String,
        finishedAtMillis: Long,
    ) {
        preferences[Keys.refreshQueued] = false
        preferences[Keys.newsRefreshing] = false
        preferences[Keys.weatherRefreshing] = false
        preferences[Keys.refreshStartedAtMillis] = 0L
        preferences[Keys.lastRefreshFinishedAtMillis] = finishedAtMillis
        preferences[Keys.lastRefreshResult] = result.name
        preferences[Keys.lastRefreshMessage] = message
    }

    private fun nextGeneration(current: Long): Long {
        return if (current == Long.MAX_VALUE) 1L else current + 1L
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
        val refreshGeneration = longPreferencesKey("refresh_generation")
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
        val lastCurrentLocationAtMillis = longPreferencesKey("last_current_location_at_millis")
        val launcherAppSlotsJson = stringPreferencesKey("launcher_app_slots_json")
        val launcherAppsJson = stringPreferencesKey("launcher_apps_json")
    }
}
