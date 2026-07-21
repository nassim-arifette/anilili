package com.miruronative.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedPlaybackModeTest {
    @Test
    fun `managed embeds enable app-owned playback features`() {
        val mode = EmbedPlaybackMode.MANAGED

        assertTrue(mode.exposesNativeBridge)
        assertTrue(mode.restoresPosition)
        assertTrue(mode.controlsPlayback)
        assertTrue(mode.automatesEpisode)
    }

    @Test
    fun `generic website fallback cannot mutate managed playback state`() {
        val mode = EmbedPlaybackMode.UNMANAGED

        assertFalse(mode.exposesNativeBridge)
        assertFalse(mode.restoresPosition)
        assertFalse(mode.controlsPlayback)
        assertFalse(mode.automatesEpisode)
    }
}
