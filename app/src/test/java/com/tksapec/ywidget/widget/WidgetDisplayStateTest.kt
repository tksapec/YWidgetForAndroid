package com.tksapec.ywidget.widget

import com.tksapec.ywidget.data.REFRESH_ACTIVE_TIMEOUT_MILLIS
import com.tksapec.ywidget.data.NewsItem
import com.tksapec.ywidget.data.WidgetSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetDisplayStateTest {
    @Test
    fun neverFetchedAndIdleDoesNotShowFetching() {
        val text = emptyNewsText(WidgetSettings(), now = 10_000L)

        assertEquals("未取得 / ↻で更新", text)
        assertFalse(text.contains("取得中"))
    }

    @Test
    fun activeQueueShowsQueued() {
        val settings = WidgetSettings(refreshQueued = true, refreshStartedAtMillis = 1_000L)

        assertEquals("更新予約中...", statusText(settings, now = 2_000L))
    }

    @Test
    fun staleQueueDoesNotShowQueued() {
        val settings = WidgetSettings(refreshQueued = true, refreshStartedAtMillis = 1_000L)

        assertEquals(
            "前回更新が中断されました",
            statusText(settings, now = 1_000L + REFRESH_ACTIVE_TIMEOUT_MILLIS),
        )
    }

    @Test
    fun staleNewsRefreshDoesNotShowUpdating() {
        val settings = WidgetSettings(newsRefreshing = true, refreshStartedAtMillis = 1_000L)

        assertEquals(
            "前回更新が中断されました",
            emptyNewsText(settings, now = 1_000L + REFRESH_ACTIVE_TIMEOUT_MILLIS),
        )
    }

    @Test
    fun firstFetchFailureHasNoFakeSuccessTime() {
        val settings = WidgetSettings(lastNewsError = "failed")

        assertEquals("ニュース取得失敗", statusText(settings, now = 10_000L))
    }

    @Test
    fun laterFailureKeepsLastSuccessTime() {
        val settings = WidgetSettings(newsUpdatedAtMillis = 1_000L, lastNewsError = "failed")

        val text = statusText(settings, now = 10_000L)
        assertTrue(text.startsWith("最終成功: "))
        assertTrue(text.endsWith(" / 更新失敗"))
        assertFalse(text.contains("--:--"))
    }

    @Test
    fun activeRefreshKeepsExistingNewsAndUsesSupplementalStatus() {
        val news = NewsItem("Headline", "https://example.com/news")
        val settings = WidgetSettings(
            news = listOf(news),
            newsUpdatedAtMillis = 1_000L,
            newsRefreshing = true,
            refreshStartedAtMillis = 2_000L,
        )

        assertEquals(listOf(news), newsForDisplay(settings))
        val text = statusText(settings, now = 3_000L)
        assertTrue(text.startsWith("更新中 / 最終: "))
        assertFalse(text.contains("ニュース更新中..."))
    }

    @Test
    fun completedRefreshFlagsDoNotOverrideSuccessfulNewsStatus() {
        val settings = WidgetSettings(
            news = listOf(NewsItem("Headline", "https://example.com/news")),
            newsUpdatedAtMillis = 1_000L,
            newsRefreshing = true,
            refreshStartedAtMillis = 2_000L,
            lastRefreshFinishedAtMillis = 3_000L,
            lastRefreshResult = com.tksapec.ywidget.data.RefreshResult.Success,
        )

        assertTrue(statusText(settings, now = 4_000L).startsWith("更新: "))
    }
}
