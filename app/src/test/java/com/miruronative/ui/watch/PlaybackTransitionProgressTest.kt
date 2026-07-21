package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTransitionProgressTest {

    private val currentIdentity = PlaybackIdentity(
        animeId = 101,
        episodeNumber = 6.0,
        generation = 12,
        mediaId = "https://cdn.example/episode-6.m3u8",
    )
    private val currentTarget = ActivePlaybackTarget(
        animeId = 101,
        episodeNumber = 6.0,
        generation = 12,
        mediaIds = setOf(currentIdentity.mediaId),
    )
    private val progress = PlaybackProgressSnapshot(
        identity = currentIdentity,
        positionMs = 321_000L,
        durationMs = 1_440_000L,
    )

    @Test
    fun `confirmed current position is persisted before playback is invalidated`() {
        val events = mutableListOf<String>()

        flushProgressBeforeTransition(
            candidate = progress,
            confirmedIdentity = currentIdentity,
            activeTarget = currentTarget,
            persist = { events += "persist:${it.positionMs}" },
            transition = { events += "invalidate" },
        )

        assertEquals(listOf("persist:321000", "invalidate"), events)
    }

    @Test
    fun `unconfirmed position never writes history while transition still proceeds`() {
        val events = mutableListOf<String>()

        flushProgressBeforeTransition(
            candidate = progress,
            confirmedIdentity = null,
            activeTarget = currentTarget,
            persist = { events += "persist" },
            transition = { events += "invalidate" },
        )

        assertEquals(listOf("invalidate"), events)
    }

    @Test
    fun `late position from replaced episode is rejected before transition`() {
        val events = mutableListOf<String>()

        flushProgressBeforeTransition(
            candidate = progress.copy(
                identity = currentIdentity.copy(
                    episodeNumber = 5.0,
                    generation = 11,
                    mediaId = "https://cdn.example/episode-5.m3u8",
                ),
            ),
            confirmedIdentity = currentIdentity.copy(episodeNumber = 5.0, generation = 11),
            activeTarget = currentTarget,
            persist = { events += "persist" },
            transition = { events += "invalidate" },
        )

        assertEquals(listOf("invalidate"), events)
    }

    @Test
    fun `resolving state represented by no active target rejects queued callbacks`() {
        val events = mutableListOf<String>()

        flushProgressBeforeTransition(
            candidate = progress,
            confirmedIdentity = currentIdentity,
            activeTarget = null,
            persist = { events += "persist" },
            transition = { events += "replace" },
        )

        assertEquals(listOf("replace"), events)
    }
}
