package com.tksapec.ywidget.network

import android.util.Log
import com.tksapec.ywidget.data.NewsCategory
import com.tksapec.ywidget.data.NewsItem
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class RssClient(
    private val parserFactory: () -> XmlPullParser = {
        XmlPullParserFactory.newInstance().newPullParser()
    },
) {
    fun fetch(category: NewsCategory): List<NewsItem> {
        val connection = URL(category.rssUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "YWidgetForAndroid/1.1 (Android RSS Widget)")
        connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*")

        val items = connection.useInputStream { parse(it) }
        if (items.isEmpty()) throw EmptyRssException(category.name)
        return items
    }

    internal fun parse(inputStream: InputStream): List<NewsItem> {
        try {
            val parser = parserFactory()
            parser.setInput(inputStream, null)

            val items = mutableListOf<NewsItem>()
            var insideItem = false
            var currentTitle: String? = null
            var currentLink: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "item" -> insideItem = true
                            "title" -> if (insideItem) currentTitle = parser.nextText().trim()
                            "link" -> if (insideItem) currentLink = parser.nextText().trim()
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        if (parser.name == "item") {
                            val title = currentTitle
                            val link = currentLink
                            if (!title.isNullOrBlank() && !link.isNullOrBlank()) {
                                items += NewsItem(title = title, url = link)
                            }
                            insideItem = false
                            currentTitle = null
                            currentLink = null
                        }
                    }
                }
                parser.next()
            }
            return items
        } catch (error: Throwable) {
            if (error is RssException) throw error
            throw RssParseException(error)
        }
    }

    private inline fun <T> HttpURLConnection.useInputStream(block: (InputStream) -> T): T {
        return try {
            if (responseCode !in 200..299) {
                val body = runCatching {
                    errorStream?.bufferedReader()?.use { it.readText().take(240) }.orEmpty()
                }.getOrDefault("")
                Log.w("RssClient", "RSS HTTP $responseCode: $body")
                throw RssHttpException(responseCode)
            }
            inputStream.use(block)
        } finally {
            disconnect()
        }
    }
}

sealed class RssException(message: String, cause: Throwable? = null) : IOException(message, cause)

class RssHttpException(val responseCode: Int) : RssException("RSS HTTP $responseCode")

class RssParseException(cause: Throwable) : RssException("RSS XML parse error", cause)

class EmptyRssException(category: String) : RssException("RSS empty: $category")
