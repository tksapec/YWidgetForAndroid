package com.example.yahoonewswidget.network

import com.example.yahoonewswidget.data.NewsCategory
import com.example.yahoonewswidget.data.NewsItem
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

class RssClient {
    fun fetch(category: NewsCategory): List<NewsItem> {
        val connection = URL(category.rssUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "YahooNewsWidget/1.0")

        return connection.useInputStream { parse(it) }
    }

    private fun parse(inputStream: InputStream): List<NewsItem> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
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
    }

    private inline fun <T> HttpURLConnection.useInputStream(block: (InputStream) -> T): T {
        return try {
            if (responseCode !in 200..299) error("RSS request failed: HTTP $responseCode")
            inputStream.use(block)
        } finally {
            disconnect()
        }
    }
}
