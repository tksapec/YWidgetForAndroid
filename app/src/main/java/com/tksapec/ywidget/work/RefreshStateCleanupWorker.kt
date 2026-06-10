package com.tksapec.ywidget.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tksapec.ywidget.data.REFRESH_ACTIVE_TIMEOUT_MILLIS
import com.tksapec.ywidget.data.WidgetPreferences
import com.tksapec.ywidget.data.shouldCleanupStaleRefreshQueue
import com.tksapec.ywidget.widget.safeUpdateAll
import java.util.concurrent.TimeUnit

class RefreshStateCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val preferences = WidgetPreferences(applicationContext)
        val settings = preferences.currentSettings()
        if (settings.shouldCleanupStaleRefreshQueue(System.currentTimeMillis())) {
            preferences.markRefreshStale()
            safeUpdateAll(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_REFRESH_STATE_CLEANUP_WORK = "ywidget_refresh_state_cleanup"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<RefreshStateCleanupWorker>()
                .setInitialDelay(REFRESH_ACTIVE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_REFRESH_STATE_CLEANUP_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_REFRESH_STATE_CLEANUP_WORK)
        }
    }
}
