package com.tksapec.ywidget.work

import android.content.Context
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.tksapec.ywidget.data.WidgetPreferences
import com.tksapec.ywidget.widget.safeUpdateAll
import kotlinx.coroutines.CancellationException

class WidgetRefreshSetupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        return try {
            if (inputData.getBoolean(INPUT_WIDGETS_DISABLED, false)) {
                RainAlertWorker.cancelAndAwait(context)
                WidgetPreferences(context).clearRefreshState()
                safeUpdateAll(context)
                return Result.success()
            }

            if (placedWidgetIds(context).isEmpty()) return Result.success()

            RefreshWorker.schedulePeriodicFromSettings(context)
            if (inputData.getBoolean(INPUT_REFRESH_IMMEDIATELY, false)) {
                RefreshWorker.enqueueImmediateByUser(context)
                RainAlertWorker.enqueueImmediateIfConfigured(context)
            } else {
                RainAlertWorker.scheduleFromSettings(context)
                RefreshWorker.enqueueImmediateIfDueFromSettings(context)
            }
            safeUpdateAll(context)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Failed to configure widget refresh", error)
            safeUpdateAll(context)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WidgetRefreshSetup"
        private const val UNIQUE_WORK = "ywidget_refresh_setup"
        private const val INPUT_REFRESH_IMMEDIATELY = "refresh_immediately"
        private const val INPUT_WIDGETS_DISABLED = "widgets_disabled"

        internal fun buildSetupInput(refreshImmediately: Boolean, widgetsDisabled: Boolean): Data =
            androidx.work.workDataOf(
                INPUT_REFRESH_IMMEDIATELY to refreshImmediately,
                INPUT_WIDGETS_DISABLED to widgetsDisabled,
            )

        fun enqueue(context: Context, refreshImmediately: Boolean, widgetsDisabled: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<WidgetRefreshSetupWorker>()
                .setInputData(buildSetupInput(refreshImmediately, widgetsDisabled))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

fun shouldRunWidgetRefreshSetup(action: String?): Boolean = when (action) {
    "android.appwidget.action.APPWIDGET_ENABLED",
    "android.appwidget.action.APPWIDGET_UPDATE",
    android.content.Intent.ACTION_BOOT_COMPLETED,
    android.content.Intent.ACTION_MY_PACKAGE_REPLACED,
    android.content.Intent.ACTION_TIME_CHANGED,
    android.content.Intent.ACTION_TIMEZONE_CHANGED,
    -> true
    else -> false
}

private fun placedWidgetIds(context: Context): IntArray =
    AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, com.tksapec.ywidget.widget.YWidgetReceiver::class.java))
