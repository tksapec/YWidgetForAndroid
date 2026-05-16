package com.example.yahoonewswidget.widget

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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        Spacer(GlanceModifier.height(6.dp))
        Divider()
        Spacer(GlanceModifier.height(4.dp))
        NewsList(
            settings = settings,
        )
        Spacer(GlanceModifier.height(4.dp))
        Divider()
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = statusText(settings),
            style = TextStyle(
                color = ColorProvider(if (settings.lastNewsError == null) Color(0xFFB8B8B8) else Color(0xFFFFC268)),
                fontSize = 11.sp,
            ),
            maxLines = 1,
        )
    }
}

@Composable
private fun Header(settings: WidgetSettings) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderTitle(settings)
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
private fun ColumnScope.NewsList(settings: WidgetSettings) {
    val modifier = GlanceModifier.defaultWeight().fillMaxWidth()
    val news = settings.news.take(minOf(settings.displayCount, settings.displayStyle.maxItems))

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
            val isRead = item.url in settings.readArticleUrls
            Text(
                text = "\u30FB${item.title}",
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .padding(vertical = settings.displayStyle.verticalPaddingDp.dp)
                    .clickable(openUrlAction(item.url)),
                style = TextStyle(
                    color = ColorProvider(if (isRead) Color(0xFF8D8D8D) else Color(0xFFF2F2F2)),
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

class RefreshAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        RefreshWorker.enqueueImmediate(context)
    }
}

class OpenUrlAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val url = parameters[UrlParameterKey] ?: return
        if (url != YAHOO_TOP_URL) {
            WidgetPreferences(context).markArticleRead(url)
            YahooNewsWidget().updateAll(context)
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}

class YahooNewsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = YahooNewsWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.enqueueImmediate(context)
        RefreshWorker.schedulePeriodic(context, 60L)
    }
}
