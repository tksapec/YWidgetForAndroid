package com.tksapec.ywidget.network

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import com.tksapec.ywidget.data.NewsCategory
import org.kxml2.io.KXmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RssClientTest {
    private val client = RssClient { KXmlParser() }

    private class FakeRssConnection : HttpURLConnection(java.net.URL("https://example.com/rss")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = 200
        override fun getInputStream(): InputStream = ByteArrayInputStream(
            "<rss><channel><item><title>Title</title><link>https://example.com/a</link></item></channel></rss>"
                .toByteArray(),
        )
    }

    @Test
    fun fetchAppliesConnectionTimeoutsBeforeReadingResponse() {
        val connection = FakeRssConnection()

        client.fetch(NewsCategory.Top, connection)

        assertEquals(10_000, connection.connectTimeout)
        assertEquals(10_000, connection.readTimeout)
    }

    @Test
    fun parseReturnsNewsItems() {
        val xml = """<rss><channel><item><title>Title</title><link>https://example.com/a</link></item></channel></rss>"""

        val items = client.parse(ByteArrayInputStream(xml.toByteArray()))

        assertEquals(1, items.size)
        assertEquals("Title", items.single().title)
    }

    @Test
    fun malformedXmlIsReportedAsParseError() {
        val error = runCatching {
            client.parse(ByteArrayInputStream("<rss><item></rss>".toByteArray()))
        }.exceptionOrNull()

        assertTrue(error is RssParseException)
    }

    @Test
    fun rssFailureTypesRemainDistinct() {
        assertEquals("RSS HTTP 500", RssHttpException(500).message)
        assertEquals("RSS empty: Top", EmptyRssException("Top").message)
    }
}
