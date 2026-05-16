package com.example.yahoonewswidget.data

import kotlinx.serialization.Serializable

@Serializable
data class NewsItem(
    val title: String,
    val url: String,
)

data class WidgetSettings(
    val category: NewsCategory = NewsCategory.Top,
    val displayCount: Int = 4,
    val updateIntervalMinutes: Long = 60,
    val news: List<NewsItem> = emptyList(),
    val newsUpdatedAtMillis: Long = 0L,
    val weatherEnabled: Boolean = true,
    val weatherCode: Int? = null,
    val temperatureCelsius: Double? = null,
    val weatherUpdatedAtMillis: Long = 0L,
)

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
