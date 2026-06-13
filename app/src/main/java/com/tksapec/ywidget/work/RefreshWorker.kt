package com.tksapec.ywidget.work

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.BackoffPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tksapec.ywidget.data.PARTIAL_NEWS_ERROR_MESSAGE
import com.tksapec.ywidget.data.LOCATION_PERMISSION_DENIED_MESSAGE
import com.tksapec.ywidget.data.NewsItem
import com.tksapec.ywidget.data.RefreshResult
import com.tksapec.ywidget.data.WeatherLocationMode
import com.tksapec.ywidget.data.WidgetPreferences
import com.tksapec.ywidget.data.WidgetSettings
import com.tksapec.ywidget.data.CURRENT_LOCATION_UNAVAILABLE_MESSAGE
import com.tksapec.ywidget.data.isRefreshDue
import com.tksapec.ywidget.data.hasStaleRefreshState
import com.tksapec.ywidget.data.summarizeNewsFetchResults
import com.tksapec.ywidget.data.classifyNewsRefresh
import com.tksapec.ywidget.data.needsRefreshStateCleanupAfterFinish
import com.tksapec.ywidget.data.refreshDiagnosticSummary
import com.tksapec.ywidget.data.userFacingWeatherErrorMessage
import com.tksapec.ywidget.network.RssClient
import com.tksapec.ywidget.network.WeatherClient
import com.tksapec.ywidget.widget.safeUpdateAll
import com.tksapec.ywidget.widget.redrawAllWidgetsAfterRefreshFinished
import com.google.android.gms.tasks.CancellationTokenSource
import java.io.IOException
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val preferences = WidgetPreferences(applicationContext)
        var retryNeeded = false
        var result = Result.success()
        var finalRefreshResult = RefreshResult.Success
        var finalRefreshMessage = "更新完了"

        try {
            prepareRefreshWork(
                markRunning = { preferences.markRefreshRunning() },
                enqueueCleanup = { RefreshStateCleanupWorker.enqueue(applicationContext) },
                shouldUpdateWidget = {
                    shouldRedrawWhenRefreshStarts(preferences.currentSettings())
                },
                updateWidget = { safeUpdateAll(applicationContext) },
            )
            withContext(Dispatchers.IO) {
                val settings = preferences.currentSettings()
                val rssClient = RssClient()
                val categoryResults: List<kotlin.Result<List<NewsItem>>> =
                    withTimeoutOrNull(NEWS_TOTAL_TIMEOUT_MILLIS) {
                        coroutineScope {
                            settings.selectedCategories.map { category ->
                                async(Dispatchers.IO) {
                                    runCatching {
                                        withTimeoutOrNull(NEWS_CATEGORY_TIMEOUT_MILLIS) {
                                            rssClient.fetch(category)
                                        } ?: throw SocketTimeoutException("RSS category timeout: ${category.name}")
                                    }
                                }
                            }.map { it.await() }
                        }
                    } ?: settings.selectedCategories.map {
                        kotlin.Result.failure(SocketTimeoutException("RSS total timeout"))
                    }
                val firstCancellation = categoryResults
                    .mapNotNull { it.exceptionOrNull() as? CancellationException }
                    .firstOrNull()
                if (firstCancellation != null) throw firstCancellation

                val newsSummary = summarizeNewsFetchResults(categoryResults)
                val newsOutcome = classifyNewsRefresh(newsSummary)

                if (newsSummary.hasNews) {
                    preferences.saveNews(
                        news = newsSummary.news,
                        updatedAtMillis = System.currentTimeMillis(),
                        warningMessage = PARTIAL_NEWS_ERROR_MESSAGE.takeIf {
                            newsSummary.failedCategoryCount > 0
                        },
                    )
                    logRefreshState("after saveNews", preferences)
                    safeUpdateAll(applicationContext)
                    if (newsOutcome.result != RefreshResult.Success) {
                        finalRefreshResult = newsOutcome.result
                        finalRefreshMessage = newsOutcome.message
                    }
                    if (newsSummary.failures.any { it.isTransientFailure() }) retryNeeded = true
                } else {
                    preferences.saveNewsError("\u30CB\u30E5\u30FC\u30B9\u53D6\u5F97\u5931\u6557")
                    logRefreshState("after saveNewsError", preferences)
                    safeUpdateAll(applicationContext)
                    finalRefreshResult = newsOutcome.result
                    finalRefreshMessage = newsOutcome.message
                    retryNeeded = newsSummary.failures.isEmpty() ||
                        newsSummary.failures.any { it.isTransientFailure() }
                }

                if (settings.weatherEnabled && settings.weatherLocationMode != WeatherLocationMode.Disabled) {
                    preferences.updateWeatherRefreshing(true)
                    if (shouldRedrawWhenWeatherRefreshStarts(settings)) {
                        safeUpdateAll(applicationContext)
                    }
                    runCatching {
                        resolveWeatherTarget(settings = settings, preferences = preferences)
                    }.onSuccess { target ->
                        runCatching {
                            WeatherClient().fetch(target.latitude, target.longitude)
                        }.onSuccess { weather ->
                            preferences.saveWeather(
                                code = weather.code,
                                temperatureCelsius = weather.temperatureCelsius,
                                locationLabel = target.label,
                                updatedAtMillis = System.currentTimeMillis(),
                            )
                            logRefreshState("after saveWeather", preferences)
                            safeUpdateAll(applicationContext)
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            preferences.saveWeatherError(userFacingWeatherErrorMessage(error))
                            logRefreshState("after saveWeatherError", preferences)
                            safeUpdateAll(applicationContext)
                            if (finalRefreshResult == RefreshResult.Success) {
                                finalRefreshResult = RefreshResult.PartialSuccess
                                finalRefreshMessage = "天気取得失敗"
                            }
                            if (error.isTransientFailure()) retryNeeded = true
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        preferences.saveWeatherError(userFacingWeatherErrorMessage(error))
                        logRefreshState("after saveWeatherTargetError", preferences)
                        safeUpdateAll(applicationContext)
                        if (finalRefreshResult == RefreshResult.Success) {
                            finalRefreshResult = RefreshResult.PartialSuccess
                            finalRefreshMessage = "天気取得失敗"
                        }
                    }
                }
            }
            result = if (retryNeeded) Result.retry() else Result.success()
        } catch (error: CancellationException) {
            finalRefreshResult = RefreshResult.Cancelled
            finalRefreshMessage = "更新が中断されました"
            throw error
        } catch (error: Throwable) {
            Log.e("RefreshWorker", "Refresh failed", error)
            finalRefreshResult = RefreshResult.Failed
            finalRefreshMessage = "更新失敗"
            result = if (error.isTransientFailure()) Result.retry() else Result.failure()
        } finally {
            withContext(NonCancellable) {
                finishRefreshAndRedraw(
                    finishRefresh = {
                        preferences.finishRefresh(finalRefreshResult, finalRefreshMessage)
                    },
                    readSettings = { preferences.currentSettings() },
                    clearRefreshState = { preferences.clearRefreshState() },
                    redrawWidgets = {
                        redrawAllWidgetsAfterRefreshFinished(applicationContext)
                    },
                    logState = { stage, state ->
                        Log.d("RefreshWorker", "$stage: ${state.refreshDiagnosticSummary()}")
                    },
                    logFailure = { message, error -> Log.w("RefreshWorker", message, error) },
                    logWarning = { message -> Log.w("RefreshWorker", message) },
                )
            }
        }

        return result
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun logRefreshState(stage: String, preferences: WidgetPreferences) {
        runCatching { preferences.currentSettings() }
            .onSuccess { Log.d("RefreshWorker", "$stage: ${it.refreshDiagnosticSummary()}") }
            .onFailure { Log.w("RefreshWorker", "Failed to read refresh state for $stage", it) }
    }

    private fun locationPriority(): Int {
        return Priority.PRIORITY_BALANCED_POWER_ACCURACY
    }

    private suspend fun resolveWeatherTarget(
        settings: WidgetSettings,
        preferences: WidgetPreferences,
    ): WeatherTarget {
        return when (settings.weatherLocationMode) {
            WeatherLocationMode.Current -> resolveCurrentLocation(settings, preferences)
            WeatherLocationMode.Fixed -> resolveFixedLocation(settings, preferences)
            WeatherLocationMode.Disabled -> error("\u5929\u6C17\u8868\u793A\u306A\u3057")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun resolveCurrentLocation(
        settings: WidgetSettings,
        preferences: WidgetPreferences,
    ): WeatherTarget {
        val locationPermissionGranted = hasLocationPermission()
        if (!locationPermissionGranted) return selectCurrentWeatherTarget(false, null, settings)
        val location = try {
            withTimeoutOrNull(CURRENT_LOCATION_TOTAL_TIMEOUT_MILLIS) {
                val client = LocationServices.getFusedLocationProviderClient(applicationContext)
                withTimeoutOrNull(LAST_LOCATION_TIMEOUT_MILLIS) {
                    client.lastLocation.await()
                } ?: run {
                    val cancellationTokenSource = CancellationTokenSource()
                    try {
                        withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MILLIS) {
                            client.getCurrentLocation(locationPriority(), cancellationTokenSource.token).await()
                        }
                    } finally {
                        cancellationTokenSource.cancel()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.w("RefreshWorker", "Current location lookup failed", error)
            null
        }

        val currentTarget = location?.let {
            val label = reverseGeocodeSafely(it.latitude, it.longitude)
            preferences.saveCurrentLocation(it.latitude, it.longitude, label)
            WeatherTarget(it.latitude, it.longitude, label)
        }
        if (currentTarget == null && settings.lastCurrentLatitude != null && settings.lastCurrentLongitude != null) {
            Log.w("RefreshWorker", "Current location unavailable; using cached location")
        }
        return selectCurrentWeatherTarget(locationPermissionGranted, currentTarget, settings)
    }

    private suspend fun resolveFixedLocation(
        settings: WidgetSettings,
        preferences: WidgetPreferences,
    ): WeatherTarget {
        val query = settings.fixedLocationQuery.trim()
        if (query.isBlank()) {
            error("\u56FA\u5B9A\u5730\u57DF\u672A\u8A2D\u5B9A")
        }
        val latitude = settings.fixedLatitude
        val longitude = settings.fixedLongitude
        if (latitude != null && longitude != null) {
            return WeatherTarget(
                latitude = latitude,
                longitude = longitude,
                label = settings.locationLabel ?: query,
            )
        }

        if (!Geocoder.isPresent()) error("\u56FA\u5B9A\u5730\u57DF\u89E3\u6C7A\u5931\u6557")
        val address = withTimeoutOrNull(GEOCODE_TIMEOUT_MILLIS) {
            geocodeLocationName(query)
        } ?: error("\u56FA\u5B9A\u5730\u57DF\u89E3\u6C7A\u5931\u6557")
        val label = address.toLocationLabel().ifBlank { query }
        preferences.saveFixedLocation(
            query = query,
            latitude = address.latitude,
            longitude = address.longitude,
            label = label,
        )
        return WeatherTarget(
            latitude = address.latitude,
            longitude = address.longitude,
            label = label,
        )
    }

    private suspend fun geocodeLocationName(query: String): Address? {
        val geocoder = Geocoder(applicationContext, Locale.JAPAN)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(
                    query,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            Log.w("RefreshWorker", "Geocoder failed: ${errorMessage.orEmpty()}")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 1)?.firstOrNull()
            } catch (error: IOException) {
                Log.w("RefreshWorker", "Geocoder I/O failure", error)
                null
            }
        }
    }

    private suspend fun reverseGeocodeSafely(latitude: Double, longitude: Double): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return withTimeoutOrNull(REVERSE_GEOCODE_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                Geocoder(applicationContext, Locale.JAPAN).getFromLocation(
                    latitude,
                    longitude,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            val label = addresses
                                .firstOrNull()
                                ?.toLocationLabel()
                                ?.takeIf { it.isNotBlank() }
                            if (continuation.isActive) continuation.resume(label)
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        }
    }

    private fun Address.toLocationLabel(): String {
        return listOf(adminArea, locality ?: subAdminArea)
            .filterNot { it.isNullOrBlank() }
            .distinct()
            .joinToString("")
    }

    companion object {
        private const val UNIQUE_REFRESH_WORK = "yahoo_news_widget_refresh"
        private const val UNIQUE_PERIODIC_WORK = "yahoo_news_widget_periodic_refresh"
        private const val BACKOFF_MINUTES = 10L
        private const val MINIMUM_PERIODIC_INTERVAL_MINUTES = 15L
        private const val LAST_LOCATION_TIMEOUT_MILLIS = 2_000L
        private const val CURRENT_LOCATION_TIMEOUT_MILLIS = 10_000L
        private const val CURRENT_LOCATION_TOTAL_TIMEOUT_MILLIS = 12_000L
        private const val REVERSE_GEOCODE_TIMEOUT_MILLIS = 2_000L
        private const val GEOCODE_TIMEOUT_MILLIS = 8_000L
        private const val NEWS_CATEGORY_TIMEOUT_MILLIS = 12_000L
        private const val NEWS_TOTAL_TIMEOUT_MILLIS = 20_000L

        fun enqueueImmediateByUser(context: Context) {
            enqueueImmediate(context, userEnqueuePolicy())
        }

        internal fun userEnqueuePolicy(): ExistingWorkPolicy = ExistingWorkPolicy.REPLACE

        private fun enqueueImmediate(context: Context, existingWorkPolicy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_REFRESH_WORK,
                existingWorkPolicy,
                request,
            )
        }

        suspend fun enqueueImmediateIfDueFromSettings(context: Context) {
            val settings = WidgetPreferences(context).currentSettings()
            val now = System.currentTimeMillis()
            if (settings.isRefreshDue(now)) {
                enqueueImmediate(context, periodicEnqueuePolicy(settings, now))
            }
        }

        internal fun periodicEnqueuePolicy(settings: WidgetSettings, now: Long): ExistingWorkPolicy {
            return if (settings.hasStaleRefreshState(now)) {
                ExistingWorkPolicy.REPLACE
            } else {
                ExistingWorkPolicy.KEEP
            }
        }

        suspend fun schedulePeriodicFromSettings(context: Context) {
            val settings = WidgetPreferences(context).currentSettings()
            schedulePeriodic(context, settings.updateIntervalMinutes)
        }

        fun schedulePeriodic(context: Context, intervalMinutes: Long) {
            val request = PeriodicWorkRequestBuilder<RefreshTriggerWorker>(
                periodicIntervalMinutes(intervalMinutes),
                TimeUnit.MINUTES,
            )
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancelAll(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_REFRESH_WORK)
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            RefreshStateCleanupWorker.cancel(context)
        }

        internal fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        internal fun periodicIntervalMinutes(intervalMinutes: Long): Long {
            return intervalMinutes.coerceAtLeast(MINIMUM_PERIODIC_INTERVAL_MINUTES)
        }

        private fun Throwable.isTransientFailure(): Boolean {
            if (this is SocketTimeoutException || this is UnknownHostException || this is IOException) {
                return true
            }
            val message = message.orEmpty()
            val status = Regex("""HTTP\s+(\d{3})""").find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            return status != null && status >= HttpURLConnection.HTTP_INTERNAL_ERROR
        }
    }
}

internal suspend fun prepareRefreshWork(
    markRunning: suspend () -> Unit,
    enqueueCleanup: () -> Unit,
    shouldUpdateWidget: suspend () -> Boolean,
    updateWidget: suspend () -> Unit,
) {
    markRunning()
    enqueueCleanup()
    if (shouldUpdateWidget()) updateWidget()
}

internal fun shouldRedrawWhenRefreshStarts(settings: WidgetSettings): Boolean = settings.news.isEmpty()

internal fun shouldRedrawWhenWeatherRefreshStarts(settings: WidgetSettings): Boolean {
    return settings.weatherCode == null || settings.temperatureCelsius == null
}

internal suspend fun finishRefreshAndRedraw(
    finishRefresh: suspend () -> Unit,
    readSettings: suspend () -> WidgetSettings,
    clearRefreshState: suspend () -> Unit,
    redrawWidgets: suspend () -> Boolean,
    logState: (String, WidgetSettings) -> Unit = { _, _ -> },
    logFailure: (String, Throwable) -> Unit = { _, _ -> },
    logWarning: (String) -> Unit = {},
): Boolean {
    finishRefresh()
    var afterFinish = runCatching { readSettings() }
        .onSuccess { logState("after finish", it) }
        .onFailure { logFailure("Failed to verify refresh state after finish", it) }
        .getOrNull()

    if (afterFinish?.needsRefreshStateCleanupAfterFinish() == true) {
        logWarning("Refresh flags remained after finish. Clearing again.")
        val clearSucceeded = runCatching { clearRefreshState() }
            .onFailure { logFailure("Failed to force-clear refresh state", it) }
            .isSuccess
        if (clearSucceeded) {
            afterFinish = runCatching { readSettings() }
                .onSuccess { logState("after forced clear", it) }
                .onFailure { logFailure("Failed to verify forced refresh-state clear", it) }
                .getOrNull()
        }
    }

    if (
        afterFinish != null &&
        (afterFinish.lastRefreshFinishedAtMillis <= 0L || afterFinish.lastRefreshResult == null)
    ) {
        logWarning("Refresh completion metadata is missing after finish.")
    }
    return redrawWidgets()
}

internal data class WeatherTarget(
    val latitude: Double,
    val longitude: Double,
    val label: String?,
)

internal fun selectCurrentWeatherTarget(
    locationPermissionGranted: Boolean,
    currentTarget: WeatherTarget?,
    settings: WidgetSettings,
): WeatherTarget {
    if (!locationPermissionGranted) error(LOCATION_PERMISSION_DENIED_MESSAGE)
    currentTarget?.let { return it }
    val latitude = settings.lastCurrentLatitude
    val longitude = settings.lastCurrentLongitude
    if (latitude != null && longitude != null) {
        return WeatherTarget(latitude, longitude, settings.lastCurrentLocationLabel)
    }
    error(CURRENT_LOCATION_UNAVAILABLE_MESSAGE)
}
