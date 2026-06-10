package com.tksapec.ywidget.widget

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetUpdateResultTest {
    @Test
    fun successfulUpdateSavesSuccess() = runBlocking {
        var successSaved = false
        var errorSaved: String? = null

        val result = performWidgetUpdate(
            updateAll = {},
            saveSuccess = { successSaved = true },
            saveError = { errorSaved = it },
        )

        assertTrue(result)
        assertTrue(successSaved)
        assertEquals(null, errorSaved)
    }

    @Test
    fun failedUpdateReturnsFalseAndSavesError() = runBlocking {
        var successSaved = false
        var errorSaved: String? = null

        val result = performWidgetUpdate(
            updateAll = { error("glance failed") },
            saveSuccess = { successSaved = true },
            saveError = { errorSaved = it },
        )

        assertFalse(result)
        assertFalse(successSaved)
        assertEquals("glance failed", errorSaved)
    }
}
