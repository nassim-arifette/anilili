package com.miruronative.ui.watch

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedEndPolicyTest {
    private val playbackKey = EmbedPlaybackKey(
        animeId = 42,
        provider = "allanime",
        category = "sub",
        episodeNumber = 3.0,
        sourceGeneration = 7,
    )

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
    fun `verified natural end plans an exact terminal commit`() {
        val completion = EmbedPlaybackCompletion(
            playbackKey = playbackKey,
            reportedPositionMs = 1_439_500L,
            durationMs = 1_440_000L,
            observedPlayingSamples = 20,
        )

        assertEquals(
            EmbedCompletionCommit(playbackKey, positionMs = 1_440_000L, durationMs = 1_440_000L),
            planEmbedCompletionCommit(completion, playbackKey, alreadyCommitted = false),
        )
    }

    @Test
    fun `late embed end from a previous playback generation is rejected`() {
        val completion = EmbedPlaybackCompletion(
            playbackKey = playbackKey,
            reportedPositionMs = 1_440_000L,
            durationMs = 1_440_000L,
            observedPlayingSamples = 20,
        )
        val replacementPlayback = playbackKey.copy(sourceGeneration = 8)

        assertNull(planEmbedCompletionCommit(completion, replacementPlayback, alreadyCommitted = false))
    }

    @Test
    fun `duplicate and unverified embed ends are rejected`() {
        val completion = EmbedPlaybackCompletion(
            playbackKey = playbackKey,
            reportedPositionMs = 1_440_000L,
            durationMs = 1_440_000L,
            observedPlayingSamples = 20,
        )

        assertNull(planEmbedCompletionCommit(completion, playbackKey, alreadyCommitted = true))
        assertNull(
            planEmbedCompletionCommit(
                completion.copy(reportedPositionMs = 1_300_000L),
                playbackKey,
                alreadyCommitted = false,
            ),
        )
        assertNull(
            planEmbedCompletionCommit(
                completion.copy(observedPlayingSamples = 1),
                playbackKey,
                alreadyCommitted = false,
            ),
        )
    }

    @Test
    fun `terminal persistence completes before embed autoplay navigation`() {
        val calls = mutableListOf<String>()
        val completion = EmbedPlaybackCompletion(
            playbackKey = playbackKey,
            reportedPositionMs = 1_440_000L,
            durationMs = 1_440_000L,
            observedPlayingSamples = 20,
        )

        val committed = finalizeEmbedCompletionThenNavigate(
            completion = completion,
            shouldNavigate = true,
            commit = {
                calls += "commit"
                true
            },
            navigate = { calls += "next" },
        )

        assertTrue(committed)
        assertEquals(listOf("commit", "next"), calls)
    }

    @Test
    fun `failed terminal persistence withholds embed autoplay`() {
        var advances = 0
        val completion = EmbedPlaybackCompletion(
            playbackKey = playbackKey,
            reportedPositionMs = 1_440_000L,
            durationMs = 1_440_000L,
            observedPlayingSamples = 20,
        )

        assertFalse(
            finalizeEmbedCompletionThenNavigate(
                completion = completion,
                shouldNavigate = true,
                commit = { false },
                navigate = { advances++ },
            ),
        )
        assertEquals(0, advances)
    }

    @Test
    fun `suppressed resumed end still commits completion without navigating`() {
        val calls = mutableListOf<String>()
        val completion = EmbedPlaybackCompletion(
            playbackKey = playbackKey,
            reportedPositionMs = 1_440_000L,
            durationMs = 1_440_000L,
            observedPlayingSamples = 20,
        )

        val committed = finalizeEmbedCompletionThenNavigate(
            completion = completion,
            shouldNavigate = canNavigateAfterEmbedEnd(
                lifecycleState = Lifecycle.State.RESUMED,
                automaticResumeSuppressed = true,
            ),
            commit = {
                calls += "commit"
                true
            },
            navigate = { calls += "next" },
        )

        assertTrue(committed)
        assertEquals(listOf("commit"), calls)
        assertFalse(
            canNavigateAfterEmbedEnd(
                lifecycleState = Lifecycle.State.STARTED,
                automaticResumeSuppressed = false,
            ),
        )
        assertTrue(
            canNavigateAfterEmbedEnd(
                lifecycleState = Lifecycle.State.RESUMED,
                automaticResumeSuppressed = false,
            ),
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
