package com.miruronative.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedAutoAdvanceGateTest {
    @Test
    fun requiresAutoplayAndANextEpisodeWithoutConsumingTheNavigation() {
        val gate = EmbedAutoAdvanceGate()

        assertFalse(gate.tryAdvance("episode-1", autoplay = false, hasNextEpisode = true))
        assertFalse(gate.tryAdvance("episode-1", autoplay = true, hasNextEpisode = false))
        assertTrue(gate.tryAdvance("episode-1", autoplay = true, hasNextEpisode = true))
    }

    @Test
    fun naturalEndAndAutoOutroCanAdvanceOnlyOncePerNavigation() {
        val gate = EmbedAutoAdvanceGate()

        assertTrue(gate.tryAdvance("episode-1", autoplay = true, hasNextEpisode = true))
        assertFalse(gate.tryAdvance("episode-1", autoplay = true, hasNextEpisode = true))
        assertTrue(gate.hasAdvanced("episode-1"))
        assertTrue(gate.tryAdvance("episode-2", autoplay = true, hasNextEpisode = true))
    }

    @Test
    fun progressScriptCarriesNavigationTokenAndReportsEndedDirectlyAndByPolling() {
        val script = progressPollJs(17L)

        assertTrue(script.contains("var navigationToken = '17'"))
        assertTrue(script.contains("addEventListener('ended'"))
        assertTrue(script.contains("AniliProgress.onEnded(navigationToken"))
        assertTrue(script.contains("AniliProgress.onTick(navigationToken"))
        assertTrue(script.contains("if (v.ended) reportEnded(v)"))
    }
}
