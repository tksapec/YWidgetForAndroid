package com.tksapec.ywidget.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tksapec.ywidget.data.WidgetPreferences
import com.tksapec.ywidget.data.isRefreshDue
import kotlinx.coroutines.CancellationException

class RefreshTriggerWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = WidgetPreferences(applicationContext).currentSettings()
        if (!settings.isRefreshDue(System.currentTimeMillis())) return Result.success()

        return try {
            RefreshWorker.enqueueImmediateIfDueFromSettings(applicationContext)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
