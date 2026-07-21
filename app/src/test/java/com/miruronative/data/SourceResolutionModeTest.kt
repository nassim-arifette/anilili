package com.miruronative.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResolutionModeTest {
    @Test
    fun `playback resolution may use the hidden media resolver`() {
        assertTrue(SourceResolutionMode.PLAYBACK.allowsHiddenMediaResolver)
    }

    @Test
    fun `background validation cannot use the hidden media resolver`() {
        assertFalse(SourceResolutionMode.BACKGROUND_VALIDATION.allowsHiddenMediaResolver)
    }

    @Test
    fun `background validation does not invoke browser backed resolver`() = runBlocking {
        var calls = 0

        val result = resolveHiddenMediaIfAllowed(SourceResolutionMode.BACKGROUND_VALIDATION) {
            calls++
            "media"
        }

        assertEquals(0, calls)
        assertEquals(null, result)
    }

    @Test
    fun `playback invokes browser backed resolver once`() = runBlocking {
        var calls = 0

        val result = resolveHiddenMediaIfAllowed(SourceResolutionMode.PLAYBACK) {
            calls++
            "media"
        }

        assertEquals(1, calls)
        assertEquals("media", result)
    }
}
