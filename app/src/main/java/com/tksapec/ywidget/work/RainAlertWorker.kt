package com.tksapec.ywidget.work

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.tksapec.ywidget.BuildConfig
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
import com.tksapec.ywidget.widget.safeUpdateAll
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal val rainAlertExecutionMutex = Mutex()

class RainAlertWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = rainAlertExecutionMutex.withLock {
        doWorkSerialized()
    }

    private suspend fun doWorkSerialized(): Result {
        val preferences = WidgetPreferences(applicationContext)
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

        return try {
            val liveCurrentTarget = if (settings.weatherLocationMode == WeatherLocationMode.Current) {
                resolveLiveCurrentTarget(preferences)
            } else {
                null
            }
            val target = selectRainTarget(
                settings = settings,
                currentTarget = liveCurrentTarget,
                now = System.currentTimeMillis(),
            )
            if (target == null) {
                RainAlertExpiryWorker.cancel(applicationContext)
                preferences.clearRainAlert(LOCATION_UNAVAILABLE_MESSAGE)
                safeUpdateAll(applicationContext)
                return Result.success()
            }

            val evaluatedAt = System.currentTimeMillis()
            val points = buildRainProbePoints(target.latitude, target.longitude)
            val observations = withContext(Dispatchers.IO) {
                YahooRainClient(clientId).fetch(points)
            }
            val alert = evaluateRainAlert(
                observations = observations,
                evaluatedAtMillis = evaluatedAt,
            )
            preferences.saveRainAlert(alert)
            if (alert.isActive) {
                RainAlertExpiryWorker.schedule(applicationContext, alert.updatedAtMillis)
            } else {
                RainAlertExpiryWorker.cancel(applicationContext)
            }
            safeUpdateAll(applicationContext)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Yahoo rain refresh failed", error)
            preferences.saveRainAlertError(
                error.message?.take(160) ?: error.javaClass.simpleName,
            )
            safeUpdateAll(applicationContext)
            if (shouldRetryTransientFailure(isTransientRainFailure(error), runAttemptCount)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
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
    private suspend fun resolveLiveCurrentTarget(preferences: WidgetPreferences): RainTarget? {
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

        preferences.saveCurrentLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            label = null,
            locationAtMillis = location.time,
        )
        return RainTarget(location.latitude, location.longitude)
    }

    companion object {
        private const val TAG = "RainAlertWorker"
        private const val UNIQUE_PERIODIC_WORK = "yahoo_rain_alert_periodic"
        private const val UNIQUE_IMMEDIATE_WORK = "yahoo_rain_alert_immediate"
        private const val BACKOFF_MINUTES = 10L
        private const val LAST_LOCATION_TIMEOUT_MILLIS = 2_000L
        private const val CURRENT_LOCATION_TIMEOUT_MILLIS = 8_000L
        private const val CURRENT_LOCATION_TOTAL_TIMEOUT_MILLIS = 10_000L
        private const val CLIENT_ID_MISSING_MESSAGE = "Yahoo Client ID未設定"
        private const val LOCATION_UNAVAILABLE_MESSAGE = "雨予報用の位置情報を取得できません"

        suspend fun scheduleFromSettings(context: Context) {
            val preferences = WidgetPreferences(context)
            if (BuildConfig.YAHOO_CLIENT_ID.isBlank()) {
                cancel(context)
                preferences.clearRainAlert(CLIENT_ID_MISSING_MESSAGE)
                return
            }
            val settings = preferences.currentSettings()
            if (isRainAlertConfigured(settings)) {
                schedule(context)
            } else {
                cancel(context)
                preferences.clearRainAlert()
            }
        }

        suspend fun enqueueImmediateIfConfigured(context: Context): Boolean {
            scheduleFromSettings(context)
            if (BuildConfig.YAHOO_CLIENT_ID.isBlank()) return false
            if (!isRainAlertConfigured(WidgetPreferences(context).currentSettings())) return false
            enqueueImmediate(context)
            return true
        }

        fun schedule(context: Context) {
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
            )
        }

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<RainAlertWorker>()
                .setConstraints(networkConstraints())
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
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

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}

internal data class RainTarget(
    val latitude: Double,
    val longitude: Double,
)

internal fun isRainAlertConfigured(settings: WidgetSettings): Boolean {
    return settings.weatherEnabled && settings.weatherLocationMode != WeatherLocationMode.Disabled
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
                )
            } else {
                null
            }
        }
        WeatherLocationMode.Disabled -> null
    }
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
