package com.miruronative.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedResumeScriptTest {

    @Test
    fun `resume finds videos in the document and same origin iframes`() {
        val script = embedResumeWhenReadyJs(targetSec = 42.5, navigationGeneration = 17L)

        assertTrue(script.contains("document.querySelector('video')"))
        assertTrue(script.contains("document.querySelectorAll('iframe')"))
        assertTrue(script.contains("frames[i].contentDocument"))
        assertTrue(script.contains("d.querySelector('video')"))
        assertTrue(script.contains("var target = 42.5"))
        assertTrue(script.contains("video.currentTime ="))
        assertTrue(script.contains("video.play()"))
    }

    @Test
    fun `resume retries slow initialization for a bounded period`() {
        val script = embedResumeWhenReadyJs(targetSec = 10.0, navigationGeneration = 18L)

        assertTrue(script.contains("var deadline = Date.now() + 60000"))
        assertTrue(script.contains("setTimeout(attemptResume, 250)"))
        assertTrue(script.contains("if (Date.now() >= deadline) return"))
        assertTrue(script.contains("if (video && video.readyState >= 1)"))
        assertFalse(script.contains("setInterval"))
    }

    @Test
    fun `resume remains navigation revocable without replacing capability state`() {
        val script = embedResumeWhenReadyJs(targetSec = 10.0, navigationGeneration = 123L)

        assertTrue(script.contains("window.__aniliNavigationToken !== '123'"))
        assertTrue(script.contains("window.__aniliNavigationToken === '123'"))
        assertTrue(script.contains("window.__aniliNavigationRevoked === true"))
        assertTrue(script.contains("window.__aniliNavigationRevoked !== true"))
        assertTrue(script.contains("if (!isCurrentNavigation()) return"))
        assertFalse(script.contains("window.__aniliNavigationToken = '123'"))
        assertFalse(script.contains("window.__aniliNavigationRevoked = false"))
        assertFalse(script.contains("AniliProgress"))
    }
}
