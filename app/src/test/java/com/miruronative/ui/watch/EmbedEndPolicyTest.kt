package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedEndPolicyTest {
    @Test
    fun `short preroll completion is not treated as episode completion`() {
        val preroll = EmbedEndSample(positionMs = 30_000L, durationMs = 30_000L, observedPlayingSamples = 20)

        assertFalse(isLikelyEmbedContentEnd(preroll))
    }

    @Test
    fun `plausible content must be observed playing and actually reach its end`() {
        assertFalse(isLikelyEmbedContentEnd(EmbedEndSample(1_440_000L, 1_440_000L, 2)))
        assertFalse(isLikelyEmbedContentEnd(EmbedEndSample(1_400_000L, 1_440_000L, 20)))
        assertTrue(isLikelyEmbedContentEnd(EmbedEndSample(1_439_500L, 1_440_000L, 20)))
    }

    @Test
    fun `invalid bridge values are rejected`() {
        assertNull(embedEndSampleFromSeconds(Double.NaN, 1_440.0, 20))
        assertNull(embedEndSampleFromSeconds(1_440.0, Double.POSITIVE_INFINITY, 20))
        assertNull(embedEndSampleFromSeconds(1_440.0, 1_440.0, -1))
        assertEquals(
            EmbedEndSample(1_439_500L, 1_440_000L, 20),
            embedEndSampleFromSeconds(1_439.5, 1_440.0, 20),
        )
    }

    @Test
    fun `natural end and outro can advance only once per navigation`() {
        val gate = EmbedAutoAdvanceGate()

        assertTrue(gate.tryAdvance("episode-a", autoplay = true, hasNextEpisode = true))
        assertFalse(gate.tryAdvance("episode-a", autoplay = true, hasNextEpisode = true))
        assertTrue(gate.tryAdvance("episode-b", autoplay = true, hasNextEpisode = true))
    }

    @Test
    fun `autoplay and a next episode are both required without consuming the gate`() {
        val gate = EmbedAutoAdvanceGate()

        assertFalse(gate.tryAdvance("episode-a", autoplay = false, hasNextEpisode = true))
        assertFalse(gate.tryAdvance("episode-a", autoplay = true, hasNextEpisode = false))
        assertTrue(gate.tryAdvance("episode-a", autoplay = true, hasNextEpisode = true))
    }

    @Test
    fun `progress script tags ended events and tracks the observed video`() {
        val script = progressPollJs(17L)

        assertTrue(script.contains("AniliProgress.onEnded("))
        assertTrue(script.contains("observedPlayingSamples"))
        assertTrue(script.contains("video !== observedVideo"))
        assertTrue(script.contains("key !== observedMediaKey"))
        assertTrue(script.contains("video.currentSrc || video.src"))
        assertTrue(script.contains("if (v.ended) reportEnded(v)"))
    }
}
