package com.tksapec.ywidget.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.tksapec.ywidget.data.WidgetPreferences
import com.tksapec.ywidget.data.isRefreshDue

class RefreshTriggerWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val settings = WidgetPreferences(applicationContext).currentSettings()
        if (settings.isRefreshDue(System.currentTimeMillis())) {
            RefreshWorker.enqueueImmediate(applicationContext)
        }
        return Result.success()
    }
}
