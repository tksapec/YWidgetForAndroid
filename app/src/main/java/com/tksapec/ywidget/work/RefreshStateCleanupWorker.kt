package com.tksapec.ywidget.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tksapec.ywidget.data.REFRESH_ACTIVE_TIMEOUT_MILLIS
import com.tksapec.ywidget.data.REFRESH_GENERATION_INPUT_KEY
import com.tksapec.ywidget.data.WidgetPreferences
import com.tksapec.ywidget.data.refreshGenerationMatches
import com.tksapec.ywidget.data.shouldCleanupStaleRefreshState
import com.tksapec.ywidget.widget.safeUpdateAll
import java.util.concurrent.TimeUnit

class RefreshStateCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val expectedGeneration = inputData.getLong(REFRESH_GENERATION_INPUT_KEY, INVALID_GENERATION)
        if (expectedGeneration == INVALID_GENERATION) return Result.success()

        val preferences = WidgetPreferences(applicationContext)
        val settings = preferences.currentSettings()
        if (
            refreshGenerationMatches(settings.refreshGeneration, expectedGeneration) &&
            settings.shouldCleanupStaleRefreshState(System.currentTimeMillis())
        ) {
            if (preferences.markRefreshStaleIfGeneration(expectedGeneration)) {
                safeUpdateAll(applicationContext)
            }
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_REFRESH_STATE_CLEANUP_WORK = "ywidget_refresh_state_cleanup"
        private const val INVALID_GENERATION = -1L

        fun enqueue(context: Context, refreshGeneration: Long) {
            val request = OneTimeWorkRequestBuilder<RefreshStateCleanupWorker>()
                .setInputData(workDataOf(REFRESH_GENERATION_INPUT_KEY to refreshGeneration))
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
