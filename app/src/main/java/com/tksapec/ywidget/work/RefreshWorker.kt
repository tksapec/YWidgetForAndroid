package com.tksapec.ywidget.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Build
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
import com.tksapec.ywidget.data.WeatherLocationMode
import com.tksapec.ywidget.data.WidgetPreferences
import com.tksapec.ywidget.data.WidgetSettings
import com.tksapec.ywidget.data.CURRENT_LOCATION_UNAVAILABLE_MESSAGE
import com.tksapec.ywidget.data.isRefreshDue
import com.tksapec.ywidget.data.summarizeNewsFetchResults
import com.tksapec.ywidget.data.userFacingWeatherErrorMessage
import com.tksapec.ywidget.network.RssClient
import com.tksapec.ywidget.network.WeatherClient
import com.tksapec.ywidget.widget.safeUpdateAll
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

        try {
            withContext(Dispatchers.IO) {
                val settings = preferences.currentSettings()
                val now = System.currentTimeMillis()
                val rssClient = RssClient()
                preferences.updateNewsRefreshing(true)
                safeUpdateAll(applicationContext)

                val categoryResults = settings.selectedCategories.map { category ->
                    runCatching { rssClient.fetch(category) }
                }
                val firstCancellation = categoryResults
                    .mapNotNull { it.exceptionOrNull() as? CancellationException }
                    .firstOrNull()
                if (firstCancellation != null) throw firstCancellation

                val newsSummary = summarizeNewsFetchResults(categoryResults)

                if (newsSummary.hasNews) {
                    preferences.saveNews(
                        news = newsSummary.news,
                        updatedAtMillis = now,
                        warningMessage = PARTIAL_NEWS_ERROR_MESSAGE.takeIf {
                            newsSummary.failedCategoryCount > 0
                        },
                    )
                    if (newsSummary.failures.any { it.isTransientFailure() }) retryNeeded = true
                } else {
                    preferences.saveNewsError("\u30CB\u30E5\u30FC\u30B9\u53D6\u5F97\u5931\u6557")
                    retryNeeded = newsSummary.failures.isEmpty() ||
                        newsSummary.failures.any { it.isTransientFailure() }
                }

                if (settings.weatherEnabled && settings.weatherLocationMode != WeatherLocationMode.Disabled) {
                    preferences.updateWeatherRefreshing(true)
                    safeUpdateAll(applicationContext)
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
                                updatedAtMillis = now,
                            )
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            preferences.saveWeatherError(userFacingWeatherErrorMessage(error))
                            if (error.isTransientFailure()) retryNeeded = true
                        }
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        preferences.saveWeatherError(userFacingWeatherErrorMessage(error))
                    }
                }
            }
            result = if (retryNeeded) Result.retry() else Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            result = if (error.isTransientFailure()) Result.retry() else Result.failure()
        } finally {
            withContext(NonCancellable) {
                preferences.clearRefreshState()
                safeUpdateAll(applicationContext)
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

    private fun locationPriority(): Int {
        return Priority.PRIORITY_BALANCED_POWER_ACCURACY
    }

    private suspend fun resolveWeatherTarget(
        settings: WidgetSettings,
        preferences: WidgetPreferences,
    ): WeatherTarget {
        return when (settings.weatherLocationMode) {
            WeatherLocationMode.Current -> resolveCurrentLocation()
            WeatherLocationMode.Fixed -> resolveFixedLocation(settings, preferences)
            WeatherLocationMode.Disabled -> error("\u5929\u6C17\u8868\u793A\u306A\u3057")
        }
    }

    private suspend fun resolveCurrentLocation(): WeatherTarget {
        if (!hasLocationPermission()) {
            error("\u4F4D\u7F6E\u60C5\u5831\u672A\u8A31\u53EF")
        }
        val location = withTimeoutOrNull(CURRENT_LOCATION_TOTAL_TIMEOUT_MILLIS) {
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
        } ?: error(CURRENT_LOCATION_UNAVAILABLE_MESSAGE)

        return WeatherTarget(
            latitude = location.latitude,
            longitude = location.longitude,
            label = reverseGeocodeSafely(location.latitude, location.longitude),
        )
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

        val address = Geocoder(applicationContext, Locale.JAPAN)
            .getFromLocationName(query, 1)
            ?.firstOrNull()
            ?: error("\u56FA\u5B9A\u5730\u57DF\u89E3\u6C7A\u5931\u6557")
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

    private data class WeatherTarget(
        val latitude: Double,
        val longitude: Double,
        val label: String?,
    )

    companion object {
        private const val UNIQUE_REFRESH_WORK = "yahoo_news_widget_refresh"
        private const val UNIQUE_PERIODIC_WORK = "yahoo_news_widget_periodic_refresh"
        private const val BACKOFF_MINUTES = 10L
        private const val PERIODIC_TRIGGER_MINUTES = 15L
        private const val LAST_LOCATION_TIMEOUT_MILLIS = 2_000L
        private const val CURRENT_LOCATION_TIMEOUT_MILLIS = 10_000L
        private const val CURRENT_LOCATION_TOTAL_TIMEOUT_MILLIS = 12_000L
        private const val REVERSE_GEOCODE_TIMEOUT_MILLIS = 2_000L

        fun enqueueImmediate(context: Context) {
            enqueueImmediate(context, ExistingWorkPolicy.KEEP)
        }

        fun enqueueImmediate(context: Context, existingWorkPolicy: ExistingWorkPolicy) {
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
            if (settings.isRefreshDue(System.currentTimeMillis())) {
                enqueueImmediate(context)
            }
        }

        suspend fun schedulePeriodicFromSettings(context: Context) {
            val settings = WidgetPreferences(context).currentSettings()
            schedulePeriodic(context, settings.updateIntervalMinutes)
        }

        fun schedulePeriodic(context: Context, intervalMinutes: Long) {
            val request = PeriodicWorkRequestBuilder<RefreshTriggerWorker>(
                PERIODIC_TRIGGER_MINUTES,
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
