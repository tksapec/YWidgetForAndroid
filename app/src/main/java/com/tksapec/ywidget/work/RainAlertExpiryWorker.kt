package com.tksapec.ywidget.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tksapec.ywidget.data.RainAlertLevel
import com.tksapec.ywidget.data.RainAlertState
import com.tksapec.ywidget.data.WidgetPreferences
import com.tksapec.ywidget.data.WidgetSettings
import com.tksapec.ywidget.data.isEffectiveRainAlertFresh
import com.tksapec.ywidget.data.rainAlertExpiryAtMillis
import com.tksapec.ywidget.widget.safeUpdateAll
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock

class RainAlertExpiryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = rainAlertExecutionMutex.withLock {
        val expectedUpdatedAtMillis = inputData.getLong(UPDATED_AT_INPUT_KEY, 0L)
        if (expectedUpdatedAtMillis <= 0L) return@withLock Result.success()

        val preferences = WidgetPreferences(applicationContext)
        val settings = preferences.currentSettings()
        if (
            shouldExpireRainAlert(
                settings = settings,
                expectedUpdatedAtMillis = expectedUpdatedAtMillis,
                nowMillis = System.currentTimeMillis(),
            )
        ) {
            preferences.clearRainAlert(settings.lastRainAlertError)
            safeUpdateAll(applicationContext)
        }
        Result.success()
    }

    companion object {
        private const val UNIQUE_EXPIRY_WORK = "yahoo_rain_alert_expiry"
        private const val UPDATED_AT_INPUT_KEY = "rain_alert_updated_at_millis"

        fun schedule(context: Context, alert: RainAlertState) {
            val expiryAtMillis = rainAlertExpiryAtMillis(alert)
            if (expiryAtMillis == null) {
                cancel(context)
                return
            }
            val delayMillis = (expiryAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<RainAlertExpiryWorker>()
                .setInputData(workDataOf(UPDATED_AT_INPUT_KEY to alert.updatedAtMillis))
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_EXPIRY_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_EXPIRY_WORK)
        }
    }
}

internal fun shouldExpireRainAlert(
    settings: WidgetSettings,
    expectedUpdatedAtMillis: Long,
    nowMillis: Long,
): Boolean {
    return settings.rainAlertLevel != RainAlertLevel.None &&
        settings.rainAlertUpdatedAtMillis == expectedUpdatedAtMillis &&
        !isEffectiveRainAlertFresh(
            storedLevel = settings.rainAlertLevel,
            updatedAtMillis = settings.rainAlertUpdatedAtMillis,
            rainAtMillis = settings.rainAlertRainAtMillis,
            fallbackMinutesUntilRain = settings.rainAlertMinutesUntilRain,
            nowMillis = nowMillis,
        )
}
