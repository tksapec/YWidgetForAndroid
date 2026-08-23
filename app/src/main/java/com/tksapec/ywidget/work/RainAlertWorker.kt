package com.tksapec.ywidget.work

import android.Manifest
import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.tksapec.ywidget.BuildConfig
import com.tksapec.ywidget.data.RainAlertWriteGuard
import com.tksapec.ywidget.data.WeatherLocationMode
import com.tksapec.ywidget.data.WidgetPreferences
import com.tksapec.ywidget.data.WidgetSettings
import com.tksapec.ywidget.data.buildRainProbePoints
import com.tksapec.ywidget.data.evaluateRainAlert
import com.tksapec.ywidget.data.freshLocationTimestampOrNull
import com.tksapec.ywidget.data.hasFreshCachedCurrentLocation
import com.tksapec.ywidget.data.isRetryableHttpStatus
import com.tksapec.ywidget.data.shouldRetryTransientFailure
import com.tksapec.ywidget.network.YahooRainClient
import com.tksapec.ywidget.network.YahooRainHttpException
import com.tksapec.ywidget.network.YahooRainParseException
import com.tksapec.ywidget.widget.YWidgetReceiver
import com.tksapec.ywidget.widget.safeUpdateAll
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal val rainAlertExecutionMutex = Mutex()
private val rainAlertScheduleMutex = Mutex()

class RainAlertWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = rainAlertExecutionMutex.withLock {
        doWorkSerialized()
    }

    private suspend fun doWorkSerialized(): Result {
        val preferences = WidgetPreferences(applicationContext)
        if (!hasPlacedWidgets(applicationContext)) {
            cancelAndAwait(applicationContext)
            preferences.clearRainAlert()
            return Result.success()
        }

        val clientId = BuildConfig.YAHOO_CLIENT_ID.trim()
        if (clientId.isBlank()) {
            RainAlertExpiryWorker.cancel(applicationContext)
            preferences.clearRainAlert(CLIENT_ID_MISSING_MESSAGE)
            safeUpdateAll(applicationContext)
            return Result.success()
        }

        val settings = preferences.currentSettings()
        if (!isRainAlertConfigured(settings)) {
            RainAlertExpiryWorker.cancel(applicationContext)
            preferences.clearRainAlert()
            safeUpdateAll(applicationContext)
            return Result.success()
        }

        var writeGuard = RainAlertWriteGuard(settings.refreshGeneration)
        return try {
            val guardedTarget = resolveGuardedRainTarget(settings, preferences)
            if (guardedTarget == null) {
                if (preferences.clearRainAlertIfGuard(writeGuard, LOCATION_UNAVAILABLE_MESSAGE)) {
                    RainAlertExpiryWorker.cancel(applicationContext)
                    safeUpdateAll(applicationContext)
                }
                return Result.success()
            }
            writeGuard = guardedTarget.guard

            val evaluatedAt = System.currentTimeMillis()
            val points = buildRainProbePoints(guardedTarget.target.latitude, guardedTarget.target.longitude)
            val observations = withContext(Dispatchers.IO) {
                YahooRainClient(clientId).fetch(points)
            }
            val alert = evaluateRainAlert(
                observations = observations,
                evaluatedAtMillis = evaluatedAt,
            )
            if (!preferences.saveRainAlert(alert, writeGuard)) {
                return Result.success()
            }

            if (alert.isActive) {
                RainAlertExpiryWorker.schedule(applicationContext, alert)
            } else {
                RainAlertExpiryWorker.cancel(applicationContext)
            }
            safeUpdateAll(applicationContext)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Yahoo rain refresh failed", error)
            val savedError = preferences.saveRainAlertError(
                error.message?.take(160) ?: error.javaClass.simpleName,
                writeGuard,
            )
            if (savedError) safeUpdateAll(applicationContext)
            if (shouldRetryTransientFailure(isTransientRainFailure(error), runAttemptCount)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private suspend fun resolveGuardedRainTarget(
        settings: WidgetSettings,
        preferences: WidgetPreferences,
    ): GuardedRainTarget? {
        return when (settings.weatherLocationMode) {
            WeatherLocationMode.Current -> resolveGuardedCurrentTarget(settings, preferences)
            WeatherLocationMode.Fixed -> {
                val target = selectRainTarget(
                    settings = settings,
                    currentTarget = null,
                    now = System.currentTimeMillis(),
                ) ?: resolveFixedRainTarget(settings, preferences)
                target?.let {
                    GuardedRainTarget(
                        target = it,
                        guard = RainAlertWriteGuard(settings.refreshGeneration),
                    )
                }
            }
            WeatherLocationMode.Disabled -> null
        }
    }

    private suspend fun resolveGuardedCurrentTarget(
        settings: WidgetSettings,
        preferences: WidgetPreferences,
    ): GuardedRainTarget? {
        val live = resolveLiveCurrentTarget()
        if (live != null) {
            val guard = preferences.saveRainCurrentLocationIfGeneration(
                latitude = live.latitude,
                longitude = live.longitude,
                locationAtMillis = live.locationAtMillis ?: 0L,
                expectedGeneration = settings.refreshGeneration,
            )
            if (guard != null) return GuardedRainTarget(live, guard)
        }

        val latest = preferences.currentSettings()
        if (latest.refreshGeneration != settings.refreshGeneration) return null
        val cached = selectRainTarget(
            settings = latest,
            currentTarget = null,
            now = System.currentTimeMillis(),
        ) ?: return null
        return GuardedRainTarget(
            target = cached,
            guard = RainAlertWriteGuard(
                expectedRefreshGeneration = latest.refreshGeneration,
                expectedCurrentLatitude = cached.latitude,
                expectedCurrentLongitude = cached.longitude,
                expectedCurrentLocationAtMillis = cached.locationAtMillis,
            ),
        )
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

    @SuppressLint("MissingPermission")
    private suspend fun resolveLiveCurrentTarget(): RainTarget? {
        if (!hasLocationPermission()) return null
        val client = LocationServices.getFusedLocationProviderClient(applicationContext)
        val location = try {
            withTimeoutOrNull(CURRENT_LOCATION_TOTAL_TIMEOUT_MILLIS) {
                val lastLocation = withTimeoutOrNull(LAST_LOCATION_TIMEOUT_MILLIS) {
                    client.lastLocation.await()
                }?.takeIf { location ->
                    freshLocationTimestampOrNull(
                        locationTimestampMillis = location.time,
                        nowMillis = System.currentTimeMillis(),
                    ) != null
                }
                lastLocation ?: run {
                    val cancellationTokenSource = CancellationTokenSource()
                    try {
                        withTimeoutOrNull(CURRENT_LOCATION_TIMEOUT_MILLIS) {
                            client.getCurrentLocation(
                                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                                cancellationTokenSource.token,
                            ).await()
                        }?.takeIf { current ->
                            freshLocationTimestampOrNull(
                                locationTimestampMillis = current.time,
                                nowMillis = System.currentTimeMillis(),
                            ) != null
                        }
                    } finally {
                        cancellationTokenSource.cancel()
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Current location lookup for rain alert failed", error)
            null
        } ?: return null

        return RainTarget(location.latitude, location.longitude, location.time)
    }

    private suspend fun resolveFixedRainTarget(
        settings: WidgetSettings,
        preferences: WidgetPreferences,
    ): RainTarget? {
        val query = settings.fixedLocationQuery.trim()
        if (query.isBlank() || !Geocoder.isPresent()) return null
        val address = withTimeoutOrNull(GEOCODE_TIMEOUT_MILLIS) {
            geocodeLocationName(query)
        } ?: return null
        if (preferences.currentSettings().refreshGeneration != settings.refreshGeneration) return null
        return RainTarget(address.latitude, address.longitude)
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
                            Log.w(TAG, "Rain geocoder failed: ${errorMessage.orEmpty()}")
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, 1)?.firstOrNull()
                } catch (error: IOException) {
                    Log.w(TAG, "Rain geocoder I/O failure", error)
                    null
                }
            }
        }
    }

    companion object {
        private const val TAG = "RainAlertWorker"
        private const val UNIQUE_PERIODIC_WORK = "yahoo_rain_alert_periodic"
        private const val UNIQUE_IMMEDIATE_WORK = "yahoo_rain_alert_immediate"
        private const val BACKOFF_MINUTES = 10L
        private const val LAST_LOCATION_TIMEOUT_MILLIS = 2_000L
        private const val CURRENT_LOCATION_TIMEOUT_MILLIS = 8_000L
        private const val CURRENT_LOCATION_TOTAL_TIMEOUT_MILLIS = 10_000L
        private const val GEOCODE_TIMEOUT_MILLIS = 8_000L
        private const val CLIENT_ID_MISSING_MESSAGE = "Yahoo Client ID未設定"
        private const val LOCATION_UNAVAILABLE_MESSAGE = "雨予報用の位置情報を取得できません"

        suspend fun scheduleFromSettings(context: Context) {
            rainAlertScheduleMutex.withLock {
                val preferences = WidgetPreferences(context)
                val settings = preferences.currentSettings()
                if (
                    !hasPlacedWidgets(context) ||
                    BuildConfig.YAHOO_CLIENT_ID.isBlank() ||
                    !isRainAlertConfigured(settings)
                ) {
                    cancelInternal(context)
                    preferences.clearRainAlert(
                        CLIENT_ID_MISSING_MESSAGE.takeIf { BuildConfig.YAHOO_CLIENT_ID.isBlank() },
                    )
                    return@withLock
                }
                scheduleInternal(context)
            }
        }

        suspend fun enqueueImmediateIfConfigured(context: Context): Boolean {
            return rainAlertScheduleMutex.withLock {
                val preferences = WidgetPreferences(context)
                val settings = preferences.currentSettings()
                if (
                    !hasPlacedWidgets(context) ||
                    BuildConfig.YAHOO_CLIENT_ID.isBlank() ||
                    !isRainAlertConfigured(settings)
                ) {
                    cancelInternal(context)
                    preferences.clearRainAlert(
                        CLIENT_ID_MISSING_MESSAGE.takeIf { BuildConfig.YAHOO_CLIENT_ID.isBlank() },
                    )
                    return@withLock false
                }
                scheduleInternal(context)
                enqueueImmediateInternal(context)
                true
            }
        }

        fun enqueueImmediate(context: Context) {
            val request = immediateRequest()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK)
            workManager.cancelUniqueWork(UNIQUE_IMMEDIATE_WORK)
            RainAlertExpiryWorker.cancel(context)
        }

        suspend fun cancelAndAwait(context: Context) {
            rainAlertScheduleMutex.withLock {
                cancelInternal(context)
            }
        }

        private suspend fun scheduleInternal(context: Context) {
            val request = PeriodicWorkRequestBuilder<RainAlertWorker>(
                rainAlertPeriodicIntervalMinutes(),
                TimeUnit.MINUTES,
            )
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            ).await()
        }

        private suspend fun enqueueImmediateInternal(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_IMMEDIATE_WORK,
                ExistingWorkPolicy.REPLACE,
                immediateRequest(),
            ).await()
        }

        private suspend fun cancelInternal(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK).await()
            workManager.cancelUniqueWork(UNIQUE_IMMEDIATE_WORK).await()
            RainAlertExpiryWorker.cancel(context)
        }

        private fun immediateRequest() = OneTimeWorkRequestBuilder<RainAlertWorker>()
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}

internal data class RainTarget(
    val latitude: Double,
    val longitude: Double,
    val locationAtMillis: Long? = null,
)

internal data class GuardedRainTarget(
    val target: RainTarget,
    val guard: RainAlertWriteGuard,
)

internal fun isRainAlertConfigured(settings: WidgetSettings): Boolean {
    return settings.weatherEnabled && settings.weatherLocationMode != WeatherLocationMode.Disabled
}

internal fun rainSourceKey(settings: WidgetSettings): String? {
    if (!isRainAlertConfigured(settings)) return null
    return when (settings.weatherLocationMode) {
        WeatherLocationMode.Current -> "current"
        WeatherLocationMode.Fixed -> {
            val query = settings.fixedLocationQuery.trim()
            if (query.isNotBlank()) {
                "fixed:query:$query"
            } else {
                val latitude = settings.fixedLatitude ?: return null
                val longitude = settings.fixedLongitude ?: return null
                "fixed:coordinates:$latitude,$longitude"
            }
        }
        WeatherLocationMode.Disabled -> null
    }
}

internal fun selectRainTarget(
    settings: WidgetSettings,
    currentTarget: RainTarget?,
    now: Long,
): RainTarget? {
    if (!isRainAlertConfigured(settings)) return null
    return when (settings.weatherLocationMode) {
        WeatherLocationMode.Fixed -> {
            val latitude = settings.fixedLatitude ?: return null
            val longitude = settings.fixedLongitude ?: return null
            RainTarget(latitude, longitude)
        }
        WeatherLocationMode.Current -> {
            currentTarget ?: if (settings.hasFreshCachedCurrentLocation(now)) {
                RainTarget(
                    latitude = settings.lastCurrentLatitude!!,
                    longitude = settings.lastCurrentLongitude!!,
                    locationAtMillis = settings.lastCurrentLocationAtMillis,
                )
            } else {
                null
            }
        }
        WeatherLocationMode.Disabled -> null
    }
}

internal fun hasPlacedWidgets(context: Context): Boolean {
    val appWidgetManager = AppWidgetManager.getInstance(context)
    val componentName = ComponentName(context, YWidgetReceiver::class.java)
    return appWidgetManager.getAppWidgetIds(componentName).isNotEmpty()
}

internal fun rainAlertPeriodicIntervalMinutes(): Long = 15L

internal fun isTransientRainFailure(error: Throwable): Boolean {
    return when (error) {
        is YahooRainHttpException -> isRetryableHttpStatus(error.responseCode)
        is YahooRainParseException -> false
        is SocketTimeoutException,
        is UnknownHostException,
        -> true
        is IOException -> true
        else -> false
    }
}
