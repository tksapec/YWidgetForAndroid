package com.tksapec.ywidget.widget

import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.RowScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.tksapec.ywidget.data.LauncherAppShortcut
import com.tksapec.ywidget.data.NewsItem
import com.tksapec.ywidget.data.RefreshResult
import com.tksapec.ywidget.data.WidgetPreferences
import com.tksapec.ywidget.data.WidgetSettings
import com.tksapec.ywidget.data.hasStaleRefreshState
import com.tksapec.ywidget.data.WeatherLocationMode
import com.tksapec.ywidget.data.isNewsRefreshingActive
import com.tksapec.ywidget.data.isRefreshQueuedActive
import com.tksapec.ywidget.data.isWeatherRefreshingActive
import com.tksapec.ywidget.data.refreshDiagnosticSummary
import com.tksapec.ywidget.data.weatherIconForCode
import com.tksapec.ywidget.work.RefreshStateCleanupWorker
import com.tksapec.ywidget.work.RefreshWorker
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class YWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val settings = WidgetPreferences(context).currentSettings()
        provideContent {
            YWidgetContent(settings = settings)
        }
    }
}

suspend fun safeUpdateAll(context: Context): Boolean {
    val preferences = WidgetPreferences(context)
    logWidgetState("before updateAll", preferences)
    val result = performWidgetUpdate(
        updateAll = { YWidget().updateAll(context) },
        saveSuccess = { preferences.saveWidgetUpdateSuccess() },
        saveError = { preferences.saveWidgetUpdateError(it) },
        logUpdateError = { Log.e("YWidget", "Failed to update Glance widgets", it) },
        logPersistenceError = { Log.w("YWidget", "Failed to persist widget update diagnostics", it) },
    )
    logWidgetState(if (result) "after updateAll success" else "after updateAll failure", preferences)
    return result
}

suspend fun redrawAllWidgetsAfterRefreshFinished(context: Context): Boolean {
    val preferences = WidgetPreferences(context)
    logWidgetState("before refresh-finished redraw", preferences)
    return safeUpdateAll(context)
}

private suspend fun logWidgetState(stage: String, preferences: WidgetPreferences) {
    runCatching { preferences.currentSettings() }
        .onSuccess { Log.d("YWidget", "$stage: ${it.refreshDiagnosticSummary()}") }
        .onFailure { Log.w("YWidget", "Failed to read widget state for $stage", it) }
}

internal suspend fun performWidgetUpdate(
    updateAll: suspend () -> Unit,
    saveSuccess: suspend () -> Unit,
    saveError: suspend (String) -> Unit,
    logUpdateError: (Throwable) -> Unit = {},
    logPersistenceError: (Throwable) -> Unit = {},
): Boolean {
    val updateError = runCatching { updateAll() }.exceptionOrNull()
    if (updateError != null) {
        logUpdateError(updateError)
        runCatching {
            saveError(updateError.message?.take(160) ?: updateError.javaClass.simpleName)
        }.onFailure(logPersistenceError)
        return false
    }
    runCatching { saveSuccess() }.onFailure(logPersistenceError)
    return true
}

@Composable
private fun YWidgetContent(settings: WidgetSettings) {
    val style = settings.displayStyle
    val now = System.currentTimeMillis()
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFF151515)))
            .cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = (8 + style.verticalPaddingDp).dp),
    ) {
        Header(settings, now)
        Spacer(GlanceModifier.height(4.dp))
        Divider()
        Spacer(GlanceModifier.height(2.dp))
        NewsList(
            settings = settings,
            now = now,
        )
        BottomActions(settings, now)
    }
}

@Composable
private fun Header(settings: WidgetSettings, now: Long) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderTitle(settings)
        WeatherText(settings, now)

        Text(
            text = "\u21BB",
            modifier = GlanceModifier
                .size(28.dp)
                .clickable(actionRunCallback<RefreshAction>()),
            style = TextStyle(
                color = ColorProvider(Color.White),
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.HeaderTitle(settings: WidgetSettings) {
    val categories = settings.selectedCategories.joinToString("/") { it.label }
    Text(
        text = "Yahoo!\u30CB\u30E5\u30FC\u30B9  $categories",
        modifier = GlanceModifier
            .defaultWeight()
            .clickable(actionRunCallback<OpenYahooAppAction>()),
        style = TextStyle(
            color = ColorProvider(Color.White),
            fontSize = settings.displayStyle.headerFontSp.sp,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
    )
}

@Composable
private fun WeatherText(settings: WidgetSettings, now: Long) {
    val display = weatherDisplay(settings, now) ?: return
    Text(
        text = display.text,
        modifier = GlanceModifier.padding(end = 8.dp),
        style = TextStyle(
            color = ColorProvider(if (display.isWarning) Color(0xFFFFC268) else Color(0xFFE4E4E4)),
            fontSize = display.fontSizeSp.sp,
        ),
        maxLines = 1,
    )
}

internal data class WeatherDisplay(
    val text: String,
    val isWarning: Boolean,
    val fontSizeSp: Int,
)

internal fun weatherDisplay(settings: WidgetSettings, now: Long): WeatherDisplay? {
    if (settings.weatherLocationMode == WeatherLocationMode.Disabled) return null
    val code = settings.weatherCode
    val temperature = settings.temperatureCelsius
    val error = settings.lastWeatherError
    val refreshing = settings.isWeatherRefreshingActive(now)
    if (!settings.weatherEnabled || code == null || temperature == null) {
        if (refreshing) {
            return WeatherDisplay("\u5929\u6C17\u66F4\u65B0\u4E2D...", isWarning = false, fontSizeSp = 11)
        }
        return error?.let { WeatherDisplay(it, isWarning = true, fontSizeSp = 11) }
    }

    val location = settings.locationLabel?.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty()
    val weather = "$location${weatherIconForCode(code)} ${temperature.toInt()}\u2103"
    return when {
        error != null -> WeatherDisplay("$weather / \u66F4\u65B0\u5931\u6557", isWarning = true, fontSizeSp = 10)
        else -> WeatherDisplay(weather, isWarning = false, fontSizeSp = 12)
    }
}

@Composable
private fun BottomActions(settings: WidgetSettings, now: Long) {
    val packageManager = LocalContext.current.packageManager
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        settings.launcherAppSlots
            .sortedBy { it.slotIndex }
            .mapNotNull { it.app }
            .filter { app ->
                try {
                    packageManager.getPackageInfo(app.packageName, 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
            .forEach { app -> LauncherAppButton(app) }
        Text(
            text = statusText(settings, now),
            modifier = GlanceModifier
                .defaultWeight()
                .padding(horizontal = 6.dp),
            style = TextStyle(
                color = ColorProvider(
                    if (
                        settings.lastNewsError == null &&
                        !settings.isNewsRefreshingActive(now) &&
                        !settings.isRefreshQueuedActive(now) &&
                        !settings.hasStaleRefreshState(now)
                    ) {
                        Color(0xFFB8B8B8)
                    } else {
                        Color(0xFFFFC268)
                    },
                ),
                fontSize = 10.sp,
                textAlign = TextAlign.End,
            ),
            maxLines = 1,
        )
        TodayButton()
    }
}

@Composable
private fun LauncherAppButton(app: LauncherAppShortcut) {
    Text(
        text = app.displayName.take(6),
        modifier = GlanceModifier
            .padding(top = 2.dp, end = 6.dp)
            .clickable(openLauncherAppAction(app.packageName)),
        style = TextStyle(
            color = ColorProvider(Color(0xFFE4E4E4)),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
    )
}

@Composable
private fun TodayButton() {
    Text(
        text = "\u4ECA\u65E5\u306F\u4F55\u306E\u65E5",
        modifier = GlanceModifier
            .padding(top = 2.dp)
            .clickable(actionRunCallback<OpenTodayWikipediaAction>()),
        style = TextStyle(
            color = ColorProvider(Color(0xFFE4E4E4)),
            fontSize = 11.sp,
        ),
        maxLines = 1,
    )
}

@Composable
private fun ColumnScope.NewsList(settings: WidgetSettings, now: Long) {
    val modifier = GlanceModifier.defaultWeight().fillMaxWidth()
    val news = newsForDisplay(settings)

    if (news.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emptyNewsText(settings, now),
                style = TextStyle(
                    color = ColorProvider(Color(0xFFE4E4E4)),
                    fontSize = 13.sp,
                ),
                maxLines = 1,
            )
        }
        return
    }

    LazyColumn(modifier = modifier) {
        items(news) { item ->
            Text(
                text = "\u30FB${item.title}",
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = settings.displayStyle.verticalPaddingDp.dp)
                    .clickable(openUrlAction(item.url)),
                style = TextStyle(
                    color = ColorProvider(Color(0xFFF2F2F2)),
                    fontSize = settings.displayStyle.itemFontSp.sp,
                ),
                maxLines = 1,
            )
        }
    }
}

internal fun newsForDisplay(settings: WidgetSettings): List<NewsItem> {
    return settings.news.take(settings.displayCount)
}

@Composable
private fun Divider() {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(1.dp)
            .background(ColorProvider(Color(0xFF2A2A2A))),
    ) {}
}

private val UrlParameterKey = ActionParameters.Key<String>("url")
private val PackageNameParameterKey = ActionParameters.Key<String>("package_name")
private const val YAHOO_APP_PACKAGE = "jp.co.yahoo.android.yjtop"
private const val YAHOO_TOP_URL = "https://www.yahoo.co.jp/"

private fun openUrlAction(url: String) = actionRunCallback<OpenUrlAction>(
    actionParametersOf(UrlParameterKey to url),
)

private fun openLauncherAppAction(packageName: String) = actionRunCallback<OpenLauncherAppAction>(
    actionParametersOf(PackageNameParameterKey to packageName),
)

internal fun statusText(settings: WidgetSettings, now: Long): String {
    if (settings.hasStaleRefreshState(now)) return "\u524D\u56DE\u66F4\u65B0\u304C\u4E2D\u65AD\u3055\u308C\u307E\u3057\u305F"
    if (settings.lastRefreshResult == RefreshResult.Stale) return "\u524D\u56DE\u66F4\u65B0\u304C\u4E2D\u65AD\u3055\u308C\u307E\u3057\u305F"
    if (settings.news.isEmpty()) {
        if (settings.isRefreshQueuedActive(now)) return "\u66F4\u65B0\u4E88\u7D04\u4E2D..."
        if (settings.isNewsRefreshingActive(now)) return "\u30CB\u30E5\u30FC\u30B9\u66F4\u65B0\u4E2D..."
    }
    if (settings.lastNewsError != null && settings.newsUpdatedAtMillis <= 0L) return "\u30CB\u30E5\u30FC\u30B9\u53D6\u5F97\u5931\u6557"
    if (settings.newsUpdatedAtMillis <= 0L) return "\u672A\u53D6\u5F97 / \u21BB\u3067\u66F4\u65B0"
    val updatedAt = formatUpdatedAt(settings.newsUpdatedAtMillis)
    return if (settings.lastNewsError == null) {
        "\u66F4\u65B0: $updatedAt"
    } else {
        "\u6700\u7D42\u6210\u529F: $updatedAt / \u66F4\u65B0\u5931\u6557"
    }
}

internal fun emptyNewsText(settings: WidgetSettings, now: Long): String {
    if (settings.isNewsRefreshingActive(now)) return "\u30CB\u30E5\u30FC\u30B9\u66F4\u65B0\u4E2D..."
    if (settings.isRefreshQueuedActive(now)) return "\u66F4\u65B0\u4E88\u7D04\u4E2D..."
    if (settings.hasStaleRefreshState(now)) return "\u524D\u56DE\u66F4\u65B0\u304C\u4E2D\u65AD\u3055\u308C\u307E\u3057\u305F"
    if (settings.lastRefreshResult == RefreshResult.Stale) return "\u524D\u56DE\u66F4\u65B0\u304C\u4E2D\u65AD\u3055\u308C\u307E\u3057\u305F"
    if (settings.lastNewsError != null) return "\u30CB\u30E5\u30FC\u30B9\u53D6\u5F97\u5931\u6557"
    return "\u672A\u53D6\u5F97 / \u21BB\u3067\u66F4\u65B0"
}

private fun formatUpdatedAt(updatedAtMillis: Long): String {
    if (updatedAtMillis <= 0L) return "--:--"
    return SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(updatedAtMillis))
}

private fun todayWikipediaUri(): Uri {
    val today = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"), Locale.JAPAN)
    val title = "${today.get(Calendar.MONTH) + 1}\u6708${today.get(Calendar.DAY_OF_MONTH)}\u65E5"
    return Uri.Builder()
        .scheme("https")
        .authority("ja.wikipedia.org")
        .appendPath("wiki")
        .appendPath(title)
        .build()
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val appContext = context.applicationContext
        val preferences = WidgetPreferences(appContext)
        try {
            preferences.updateRefreshQueued(true)
            RefreshStateCleanupWorker.enqueue(appContext)
            safeUpdateAll(appContext)
            RefreshWorker.enqueueImmediateByUser(appContext)
        } catch (_: Throwable) {
            preferences.finishRefresh(RefreshResult.Failed, "更新予約失敗")
            safeUpdateAll(appContext)
        }
    }
}

class OpenTodayWikipediaAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        context.tryStartActivity(
            Intent(Intent.ACTION_VIEW, todayWikipediaUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

class OpenUrlAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val url = parameters[UrlParameterKey] ?: return
        context.openYahooUrlWithFallback(url)
    }
}

class OpenYahooAppAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(YAHOO_APP_PACKAGE)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        if (!context.tryStartActivity(launchIntent)) {
            context.openYahooUrlWithFallback(YAHOO_TOP_URL)
        }
    }
}

class OpenLauncherAppAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val packageName = parameters[PackageNameParameterKey] ?: return
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.tryStartActivity(launchIntent)
    }
}

class YWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = YWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        runWidgetRefreshSetup(context, refreshImmediately = true)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        runWidgetRefreshSetup(context, refreshImmediately = false)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        RefreshWorker.cancelAll(context.applicationContext)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                WidgetPreferences(context.applicationContext).clearRefreshState()
                safeUpdateAll(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> runWidgetRefreshSetup(context, refreshImmediately = false)
        }
    }

    private fun runWidgetRefreshSetup(context: Context, refreshImmediately: Boolean) {
        if (!context.hasPlacedWidgets()) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Periodic refresh is owned by WorkManager; AppWidgetProvider only registers and kicks due work.
                RefreshWorker.schedulePeriodicFromSettings(context.applicationContext)
                if (refreshImmediately) {
                    RefreshWorker.enqueueImmediateByUser(context.applicationContext)
                } else {
                    RefreshWorker.enqueueImmediateIfDueFromSettings(context.applicationContext)
                }
                safeUpdateAll(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private fun Context.hasPlacedWidgets(): Boolean {
    val appWidgetManager = AppWidgetManager.getInstance(this)
    val componentName = ComponentName(this, YWidgetReceiver::class.java)
    return appWidgetManager.getAppWidgetIds(componentName).isNotEmpty()
}

private fun Context.openYahooUrlWithFallback(url: String) {
    val uri = Uri.parse(url)
    val yahooIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage(YAHOO_APP_PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (!tryStartActivity(yahooIntent)) {
        val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        tryStartActivity(fallbackIntent)
    }
}

private fun Context.tryStartActivity(intent: Intent?): Boolean {
    if (intent == null) return false
    return try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
}
