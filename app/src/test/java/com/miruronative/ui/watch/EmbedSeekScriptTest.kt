package com.miruronative.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedSeekScriptTest {

    @Test
    fun `seek changes time without forcing playback`() {
        val script = embedSeekJs(42.5)

        assertTrue(script.contains("var target = 42.5"))
        assertTrue(script.contains("v.currentTime ="))
        assertFalse(script.contains(".play("))
        assertFalse(script.contains(".pause("))
    }

    @Test
    fun `seek still supports same origin iframe videos`() {
        val script = embedSeekJs(10.0)

        assertTrue(script.contains("frames[i].contentDocument"))
        assertTrue(script.contains("d.querySelector('video')"))
    }

    @Test
    fun `auto skip accepts only an actual JavaScript success result`() {
        assertTrue(javascriptBooleanResult("true"))
        assertTrue(javascriptBooleanResult("  true  "))
        assertFalse(javascriptBooleanResult("false"))
        assertFalse(javascriptBooleanResult("\"true\""))
        assertFalse(javascriptBooleanResult(null))
    }
}
