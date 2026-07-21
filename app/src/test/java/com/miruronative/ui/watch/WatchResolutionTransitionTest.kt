package com.miruronative.ui.watch

import com.miruronative.data.model.Category
import com.miruronative.data.model.EpisodeItem
import com.miruronative.data.model.SourcesResult
import com.miruronative.data.model.StreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WatchResolutionTransitionTest {
    private val embedStream = StreamItem(
        url = "https://kiwi.example/embed/episode-90",
        type = "embed",
        quality = null,
        audio = "sub",
        referer = null,
        isActive = true,
        width = null,
        height = null,
    )

    private fun resolvingData(): WatchData = WatchData(
        episodes = listOf(
            EpisodeItem(
                pipeId = "episode-90",
                number = 90.0,
                title = null,
                image = null,
                filler = false,
            ),
        ),
        currentIndex = 0,
        provider = "reanime",
        category = Category.SUB,
        sourceOptions = emptyList(),
        anilistId = 101,
        sources = SourcesResult(
            streams = listOf(embedStream),
            subtitles = emptyList(),
            skip = null,
            download = null,
        ),
        chosenStream = embedStream,
        seriesTitle = "Series",
        artworkUrl = null,
        startPositionMs = 300_000L,
        playbackGeneration = 44,
        isResolving = true,
        playbackTeardownGeneration = 44,
    )

    @Test
    fun `embed tick is part of the general transition target and is flushed`() {
        val data = resolvingData().copy(isResolving = false, playbackTeardownGeneration = null)
        val identity = PlaybackIdentity(
            animeId = data.anilistId,
            episodeNumber = data.current.number,
            generation = data.playbackGeneration,
            mediaId = embedStream.url,
        )
        val snapshot = PlaybackProgressSnapshot(identity, 321_000L, 1_440_000L)
        var persisted: PlaybackProgressSnapshot? = null

        flushProgressBeforeTransition(
            candidate = snapshot,
            confirmedIdentity = identity,
            activeTarget = data.playbackTarget(),
            persist = { persisted = it },
            transition = {},
        )

        assertSame(snapshot, persisted)
    }

    @Test
    fun `failed replacement rolls retained embed forward under fresh generation`() {
        val outgoingSurfaceGeneration = 43
        val before = resolvingData()
        val refreshedOptions = listOf(
            WatchSourceOption("reanime", Category.SUB, hasCurrentEpisode = true, episodeCount = 100),
        )

        val rollback = before.rollbackAfterFailedResolution(
            refreshedSourceOptions = refreshedOptions,
            resumePositionMs = 315_000L,
            failureNotice = "No playable replacement",
        )

        assertSame(embedStream, rollback.chosenStream)
        assertEquals(44, rollback.playbackGeneration)
        assertNotEquals(outgoingSurfaceGeneration, rollback.playbackGeneration)
        assertEquals(315_000L, rollback.startPositionMs)
        assertEquals(refreshedOptions, rollback.sourceOptions)
        assertEquals("No playable replacement", rollback.notice)
        assertFalse(rollback.isResolving)
        assertNull(rollback.playbackTeardownGeneration)
    }

    @Test
    fun `playback error recovery cannot resurrect failed media`() {
        val failed = resolvingData()

        val terminal = failed.watchDataAfterFailedResolution(
            policy = ResolutionFailurePolicy.TERMINAL,
            refreshedSourceOptions = emptyList(),
            resumePositionMs = 315_000L,
            failureNotice = "No alternative source",
        )

        assertNull(terminal)
    }

    @Test
    fun `missing autoplay target cannot resurrect naturally completed previous episode`() {
        val effectivePolicy = effectiveResolutionFailurePolicy(
            requestedPolicy = ResolutionFailurePolicy.RESTORE_PREVIOUS,
            previousEpisodeNumber = 90.0,
            requestedEpisodeNumber = 91.0,
            historyEpisodeNumber = 90.0,
            historyContinuationEpisodeNumber = 91.0,
            historyCompleted = false,
        )

        val rollback = resolvingData().watchDataAfterFailedResolution(
            policy = effectivePolicy,
            refreshedSourceOptions = emptyList(),
            resumePositionMs = 1_440_000L,
            failureNotice = "Episode 91 is unavailable",
        )

        assertEquals(ResolutionFailurePolicy.TERMINAL, effectivePolicy)
        assertNull(rollback)
    }

    @Test
    fun `ordinary user next failure may still restore in progress episode`() {
        assertEquals(
            ResolutionFailurePolicy.RESTORE_PREVIOUS,
            effectiveResolutionFailurePolicy(
                requestedPolicy = ResolutionFailurePolicy.RESTORE_PREVIOUS,
                previousEpisodeNumber = 90.0,
                requestedEpisodeNumber = 91.0,
                historyEpisodeNumber = 90.0,
                historyContinuationEpisodeNumber = null,
                historyCompleted = false,
            ),
        )
    }

    @Test
    fun `failed source switch restores previous private routing`() {
        val previous = PlaybackRoutingState(
            preferred = "kiwi",
            category = Category.SUB,
            spine = resolvingData().episodes,
            globalPreferredProvider = "kiwi",
            failedProviders = setOf("old-failure"),
        )
        val attempted = PlaybackRoutingState(
            preferred = "reanime",
            category = Category.DUB,
            spine = listOf(resolvingData().current.copy(pipeId = "dub-90")),
            globalPreferredProvider = "kiwi",
            failedProviders = emptySet(),
        )

        assertEquals(
            previous,
            routingAfterResolutionFailure(
                policy = ResolutionFailurePolicy.RESTORE_PREVIOUS,
                rollback = previous,
                current = attempted,
            ),
        )
        assertEquals(
            attempted,
            routingAfterResolutionFailure(
                policy = ResolutionFailurePolicy.TERMINAL,
                rollback = previous,
                current = attempted,
            ),
        )
    }

    @Test
    fun `preferred provider is persisted only when selected provider resolves`() {
        assertEquals(
            "reanime",
            preferredProviderCommitAfterResolution("reanime", resolvedProvider = "reanime"),
        )
        assertNull(
            preferredProviderCommitAfterResolution("reanime", resolvedProvider = "kiwi"),
        )
    }
}
