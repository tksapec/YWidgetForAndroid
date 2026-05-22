package com.example.yahoonewswidget.widget

import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import com.example.yahoonewswidget.data.NewsItem
import com.example.yahoonewswidget.data.WidgetPreferences
import com.example.yahoonewswidget.data.WidgetSettings
import com.example.yahoonewswidget.data.WeatherLocationMode
import com.example.yahoonewswidget.data.weatherIconForCode
import com.example.yahoonewswidget.work.RefreshWorker
import java.util.Calendar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class YahooNewsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val settings = WidgetPreferences(context).currentSettings()
        provideContent {
            YahooNewsWidgetContent(settings = settings)
        }
    }
}

@Composable
private fun YahooNewsWidgetContent(settings: WidgetSettings) {
    val style = settings.displayStyle
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(Color(0xFF151515)))
            .cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = (8 + style.verticalPaddingDp).dp),
    ) {
        Header(settings)
        Spacer(GlanceModifier.height(4.dp))
        Divider()
        Spacer(GlanceModifier.height(2.dp))
        NewsList(
            settings = settings,
        )
        BottomActions()
    }
}

@Composable
private fun Header(settings: WidgetSettings) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderTitle(settings)
        HeaderStatus(settings)
        WeatherText(settings)

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
            .clickable(openUrlAction(YAHOO_TOP_URL)),
        style = TextStyle(
            color = ColorProvider(Color.White),
            fontSize = settings.displayStyle.headerFontSp.sp,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
    )
}

@Composable
private fun HeaderStatus(settings: WidgetSettings) {
    Text(
        text = statusText(settings),
        modifier = GlanceModifier.padding(end = 8.dp),
        style = TextStyle(
            color = ColorProvider(if (settings.lastNewsError == null) Color(0xFFB8B8B8) else Color(0xFFFFC268)),
            fontSize = 11.sp,
        ),
        maxLines = 1,
    )
}

@Composable
private fun WeatherText(settings: WidgetSettings) {
    val code = settings.weatherCode
    val temperature = settings.temperatureCelsius
    if (settings.weatherLocationMode == WeatherLocationMode.Disabled) return

    val error = settings.lastWeatherError
    if (!settings.weatherEnabled || code == null || temperature == null) {
        if (error != null) {
            Text(
                text = error,
                modifier = GlanceModifier.padding(end = 8.dp),
                style = TextStyle(
                    color = ColorProvider(Color(0xFFFFC268)),
                    fontSize = 11.sp,
                ),
                maxLines = 1,
            )
        }
        return
    }

    val location = settings.locationLabel?.takeIf { it.isNotBlank() }?.let { "$it " }.orEmpty()

    Text(
        text = "$location${weatherIconForCode(code)} ${temperature.toInt()}\u2103",
        modifier = GlanceModifier.padding(end = 8.dp),
        style = TextStyle(
            color = ColorProvider(Color(0xFFE4E4E4)),
            fontSize = 12.sp,
        ),
        maxLines = 1,
    )
}

@Composable
private fun BottomActions() {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YahooButton()
        Spacer(GlanceModifier.defaultWeight())
        TodayButton()
    }
}

@Composable
private fun YahooButton() {
    Text(
        text = "Yahoo!",
        modifier = GlanceModifier
            .padding(top = 2.dp)
            .clickable(actionRunCallback<OpenYahooAppAction>()),
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
private fun ColumnScope.NewsList(settings: WidgetSettings) {
    val modifier = GlanceModifier.defaultWeight().fillMaxWidth()
    val news = settings.news.take(settings.displayCount)

    if (news.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "\u53D6\u5F97\u4E2D",
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
private const val YAHOO_APP_PACKAGE = "jp.co.yahoo.android.yjtop"
private const val YAHOO_TOP_URL = "https://www.yahoo.co.jp/"

private fun openUrlAction(url: String) = actionRunCallback<OpenUrlAction>(
    actionParametersOf(UrlParameterKey to url),
)

private fun statusText(settings: WidgetSettings): String {
    val updatedAt = "\u66F4\u65B0: ${formatUpdatedAt(settings.newsUpdatedAtMillis)}"
    return settings.lastNewsError?.let { "$updatedAt  $it" } ?: updatedAt
}

private fun formatUpdatedAt(updatedAtMillis: Long): String {
    if (updatedAtMillis <= 0L) return "--:--"
    return SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(updatedAtMillis))
}

private fun todayWikipediaUri(): Uri {
    val today = Calendar.getInstance(Locale.JAPAN)
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
        RefreshWorker.enqueueImmediate(context)
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

class YahooNewsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = YahooNewsWidget()

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
                RefreshWorker.schedulePeriodicFromSettings(context.applicationContext)
                if (refreshImmediately) {
                    RefreshWorker.enqueueImmediate(context.applicationContext)
                } else {
                    RefreshWorker.enqueueImmediateIfDueFromSettings(context.applicationContext)
                }
                YahooNewsWidget().updateAll(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

private fun Context.hasPlacedWidgets(): Boolean {
    val appWidgetManager = AppWidgetManager.getInstance(this)
    val componentName = ComponentName(this, YahooNewsWidgetReceiver::class.java)
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
