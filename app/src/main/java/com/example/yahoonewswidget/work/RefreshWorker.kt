package com.example.yahoonewswidget.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.yahoonewswidget.data.WidgetPreferences
import com.example.yahoonewswidget.network.RssClient
import com.example.yahoonewswidget.network.WeatherClient
import com.example.yahoonewswidget.widget.YahooNewsWidget
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val preferences = WidgetPreferences(applicationContext)
        val settings = preferences.currentSettings()
        val now = System.currentTimeMillis()

        withContext(Dispatchers.IO) {
            runCatching {
                RssClient().fetch(settings.category)
            }.onSuccess { news ->
                if (news.isNotEmpty()) {
                    preferences.saveNews(news, now)
                }
            }

            if (settings.weatherEnabled && hasCoarseLocationPermission()) {
                runCatching {
                    val location = LocationServices
                        .getFusedLocationProviderClient(applicationContext)
                        .lastLocation
                        .await()
                        ?: LocationServices
                            .getFusedLocationProviderClient(applicationContext)
                            .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                            .await()

                    if (location != null) {
                        WeatherClient().fetch(location.latitude, location.longitude)
                    } else {
                        null
                    }
                }.onSuccess { weather ->
                    if (weather != null) {
                        preferences.saveWeather(
                            code = weather.code,
                            temperatureCelsius = weather.temperatureCelsius,
                            updatedAtMillis = now,
                        )
                    }
                }
            }
        }

        YahooNewsWidget().updateAll(applicationContext)
        return Result.success()
    }

    private fun hasCoarseLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val UNIQUE_REFRESH_WORK = "yahoo_news_widget_refresh"
        private const val UNIQUE_PERIODIC_WORK = "yahoo_news_widget_periodic_refresh"

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(networkConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_REFRESH_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun schedulePeriodic(context: Context, intervalMinutes: Long) {
            val safeIntervalMinutes = intervalMinutes.coerceAtLeast(30L)
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(
                safeIntervalMinutes,
                TimeUnit.MINUTES,
            )
                .setConstraints(networkConstraints())
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        private fun networkConstraints(): Constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
