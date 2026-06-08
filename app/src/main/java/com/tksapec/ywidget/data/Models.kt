package com.tksapec.ywidget.data

import kotlinx.serialization.Serializable

@Serializable
data class NewsItem(
    val title: String,
    val url: String,
)

data class WidgetSettings(
    val category: NewsCategory = NewsCategory.Top,
    val selectedCategories: Set<NewsCategory> = setOf(NewsCategory.Top),
    val displayCount: Int = 4,
    val displayStyle: DisplayStyle = DisplayStyle.Standard,
    val updateIntervalMinutes: Long = 60,
    val news: List<NewsItem> = emptyList(),
    val newsUpdatedAtMillis: Long = 0L,
    val newsRefreshing: Boolean = false,
    val refreshQueued: Boolean = false,
    val refreshStartedAtMillis: Long = 0L,
    val weatherEnabled: Boolean = false,
    val weatherLocationMode: WeatherLocationMode = WeatherLocationMode.Disabled,
    val locationLabel: String? = null,
    val fixedLocationQuery: String = "",
    val fixedLatitude: Double? = null,
    val fixedLongitude: Double? = null,
    val weatherCode: Int? = null,
    val temperatureCelsius: Double? = null,
    val weatherUpdatedAtMillis: Long = 0L,
    val weatherRefreshing: Boolean = false,
    val lastNewsError: String? = null,
    val lastWeatherError: String? = null,
    val launcherAppSlots: List<LauncherAppSlot> = emptyLauncherAppSlots(),
)

@Serializable
data class LauncherAppShortcut(
    val displayName: String,
    val packageName: String,
)

@Serializable
data class LauncherAppSlot(
    val slotIndex: Int,
    val app: LauncherAppShortcut? = null,
)

fun emptyLauncherAppSlots(): List<LauncherAppSlot> = (0..2).map { slotIndex ->
    LauncherAppSlot(slotIndex = slotIndex)
}

fun normalizeLauncherAppSlots(slots: List<LauncherAppSlot>): List<LauncherAppSlot> {
    val usedPackages = mutableSetOf<String>()
    val byIndex = slots
        .filter { it.slotIndex in 0..2 }
        .sortedBy { it.slotIndex }
        .associateBy { it.slotIndex }

    return (0..2).map { slotIndex ->
        val app = byIndex[slotIndex]?.app?.takeIf {
            it.displayName.isNotBlank() &&
                it.packageName.isNotBlank() &&
                usedPackages.add(it.packageName)
        }
        LauncherAppSlot(slotIndex = slotIndex, app = app)
    }
}

data class NewsFetchSummary(
    val news: List<NewsItem>,
    val failedCategoryCount: Int,
    val failures: List<Throwable>,
) {
    val hasNews: Boolean = news.isNotEmpty()
}

fun summarizeNewsFetchResults(results: List<Result<List<NewsItem>>>): NewsFetchSummary {
    val news = results
        .flatMap { it.getOrDefault(emptyList()) }
        .distinctBy { it.url }
    val failures = results.mapNotNull { it.exceptionOrNull() }
    val emptySuccessfulCategories = results.count {
        it.isSuccess && it.getOrDefault(emptyList()).isEmpty()
    }
    return NewsFetchSummary(
        news = news,
        failedCategoryCount = failures.size + emptySuccessfulCategories,
        failures = failures,
    )
}

fun WidgetSettings.isRefreshDue(now: Long): Boolean {
    val intervalMillis = updateIntervalMinutes.coerceAtLeast(1L) * 60_000L
    val newsDue = newsUpdatedAtMillis <= 0L || now - newsUpdatedAtMillis >= intervalMillis
    val weatherDue = weatherEnabled &&
        weatherLocationMode != WeatherLocationMode.Disabled &&
        (weatherUpdatedAtMillis <= 0L || now - weatherUpdatedAtMillis >= intervalMillis)
    return newsDue || weatherDue
}

fun WidgetSettings.isNewsRefreshingActive(now: Long): Boolean {
    return newsRefreshing && isRefreshStateActive(now)
}

fun WidgetSettings.isWeatherRefreshingActive(now: Long): Boolean {
    return weatherRefreshing && isRefreshStateActive(now)
}

fun WidgetSettings.isRefreshQueuedActive(now: Long): Boolean {
    return refreshQueued && isRefreshStateActive(now)
}

private fun WidgetSettings.isRefreshStateActive(now: Long): Boolean {
    return refreshStartedAtMillis > 0L &&
        now - refreshStartedAtMillis < REFRESH_ACTIVE_TIMEOUT_MILLIS
}

const val REFRESH_ACTIVE_TIMEOUT_MILLIS: Long = 2 * 60 * 1_000L
const val PARTIAL_NEWS_ERROR_MESSAGE: String =
    "\u4E00\u90E8\u30AB\u30C6\u30B4\u30EA\u306E\u53D6\u5F97\u306B\u5931\u6557"
const val CURRENT_LOCATION_UNAVAILABLE_MESSAGE: String =
    "\u73FE\u5728\u5730\u3092\u53D6\u5F97\u3067\u304D\u307E\u305B\u3093\u3067\u3057\u305F\u3002\u56FA\u5B9A\u5730\u57DF\u306E\u4F7F\u7528\u3092\u304A\u3059\u3059\u3081\u3057\u307E\u3059\u3002"
const val WEATHER_DATA_FORMAT_ERROR_MESSAGE: String = "\u5929\u6C17\u30C7\u30FC\u30BF\u5F62\u5F0F\u30A8\u30E9\u30FC"
const val WEATHER_CODE_ERROR_MESSAGE: String = "\u5929\u6C17\u30B3\u30FC\u30C9\u53D6\u5F97\u5931\u6557"
const val WEATHER_TEMPERATURE_ERROR_MESSAGE: String = "\u6C17\u6E29\u53D6\u5F97\u5931\u6557"
private const val GENERIC_WEATHER_ERROR_MESSAGE: String = "\u5929\u6C17\u53D6\u5F97\u5931\u6557"

fun userFacingWeatherErrorMessage(error: Throwable): String {
    val rawMessage = error.message.orEmpty()
    val diagnostic = error.toString()
    if (
        rawMessage.contains("UNAVAILABLE", ignoreCase = true) ||
        diagnostic.contains("UNAVAILABLE", ignoreCase = true)
    ) {
        return CURRENT_LOCATION_UNAVAILABLE_MESSAGE
    }
    return when (rawMessage) {
        "\u4F4D\u7F6E\u60C5\u5831\u672A\u8A31\u53EF" -> "\u4F4D\u7F6E\u60C5\u5831\u304C\u8A31\u53EF\u3055\u308C\u3066\u3044\u307E\u305B\u3093"
        "\u73FE\u5728\u5730\u53D6\u5F97\u5931\u6557",
        CURRENT_LOCATION_UNAVAILABLE_MESSAGE,
        -> CURRENT_LOCATION_UNAVAILABLE_MESSAGE
        "\u56FA\u5B9A\u5730\u57DF\u672A\u8A2D\u5B9A",
        "\u56FA\u5B9A\u5730\u57DF\u89E3\u6C7A\u5931\u6557",
        "\u5929\u6C17\u8868\u793A\u306A\u3057",
        WEATHER_DATA_FORMAT_ERROR_MESSAGE,
        WEATHER_CODE_ERROR_MESSAGE,
        WEATHER_TEMPERATURE_ERROR_MESSAGE,
        -> rawMessage
        else -> GENERIC_WEATHER_ERROR_MESSAGE
    }
}

enum class DisplayStyle(
    val label: String,
    val itemFontSp: Int,
    val headerFontSp: Int,
    val verticalPaddingDp: Int,
) {
    Compact("\u30B3\u30F3\u30D1\u30AF\u30C8", 12, 12, 1),
    Standard("\u6A19\u6E96", 14, 13, 2),
    Large("\u5927\u304D\u3081", 16, 14, 3);

    companion object {
        fun fromName(name: String): DisplayStyle = entries.firstOrNull { it.name == name } ?: Standard
    }
}

enum class WeatherLocationMode(val label: String) {
    Current("\u73FE\u5728\u5730\u3092\u4F7F\u3046"),
    Fixed("\u56FA\u5B9A\u5730\u57DF\u3092\u4F7F\u3046"),
    Disabled("\u8868\u793A\u3057\u306A\u3044");

    companion object {
        fun fromName(name: String): WeatherLocationMode = entries.firstOrNull { it.name == name } ?: Disabled
    }
}

enum class NewsCategory(
    val label: String,
    val rssUrl: String,
) {
    Top("\u4E3B\u8981", "https://news.yahoo.co.jp/rss/topics/top-picks.xml"),
    Domestic("\u56FD\u5185", "https://news.yahoo.co.jp/rss/topics/domestic.xml"),
    World("\u56FD\u969B", "https://news.yahoo.co.jp/rss/topics/world.xml"),
    Business("\u7D4C\u6E08", "https://news.yahoo.co.jp/rss/topics/business.xml"),
    It("IT", "https://news.yahoo.co.jp/rss/topics/it.xml"),
    Science("\u79D1\u5B66", "https://news.yahoo.co.jp/rss/topics/science.xml"),
    Sports("\u30B9\u30DD\u30FC\u30C4", "https://news.yahoo.co.jp/rss/topics/sports.xml");

    companion object {
        fun fromName(name: String): NewsCategory = entries.firstOrNull { it.name == name } ?: Top
    }
}

fun weatherIconForCode(code: Int): String = when (code) {
    0 -> "\u2600"
    1, 2 -> "\u26C5"
    3 -> "\u2601"
    45, 48 -> "\u9727"
    51, 53, 55, 56, 57 -> "\u9727\u96E8"
    61, 63, 65, 66, 67, 80, 81, 82 -> "\u2602"
    71, 73, 75, 77, 85, 86 -> "\u96EA"
    95, 96, 99 -> "\u96F7"
    else -> "\u2601"
}
