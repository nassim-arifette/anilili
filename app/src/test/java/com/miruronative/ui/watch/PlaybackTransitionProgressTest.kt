package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `current concrete embed position survives final commit and transition flush`() {
        val playback = EmbedPlaybackKey(21, "allanime", "sub", 12.0, sourceGeneration = 8)
        val activeEmbed = EmbedMediaIdentity(
            playbackKey = playback,
            navigationGeneration = 40,
            documentMediaId = "https://embed.example/episode-12?signature=current",
            videoIdentity = EmbedVideoIdentity(
                mediaId = "https://cdn.example/episode-12.m3u8?token=current|1440000",
                generation = 2,
            ),
        )
        val identity = checkNotNull(activeEmbed.playbackProgressIdentity())
        val target = ActivePlaybackTarget(
            animeId = playback.animeId,
            episodeNumber = playback.episodeNumber,
            generation = playback.sourceGeneration,
            mediaIds = setOf(activeEmbed.documentMediaId),
        ).scopedToActiveEmbedMedia(activeEmbed, playback)
        val snapshot = PlaybackProgressSnapshot(identity, 321_000L, 1_440_000L)
        val events = mutableListOf<String>()

        // commitPlaybackPosition uses the same acceptance predicate before its unthrottled write.
        assertTrue(acceptsPlaybackProgress(snapshot.identity, target))
        flushProgressBeforeTransition(
            candidate = snapshot,
            confirmedIdentity = identity,
            activeTarget = target,
            persist = { events += "persist:${it.positionMs}" },
            transition = { events += "invalidate" },
        )

        assertEquals(activeEmbed.documentMediaId, identity.mediaId)
        assertEquals(listOf("persist:321000", "invalidate"), events)
    }

    @Test
    fun `former concrete video generation and AniSkip hash cannot flush after handoff`() {
        val playback = EmbedPlaybackKey(21, "allanime", "sub", 12.0, sourceGeneration = 8)
        val formerEmbed = EmbedMediaIdentity(
            playbackKey = playback,
            navigationGeneration = 40,
            documentMediaId = "https://embed.example/episode-12?signature=shared",
            videoIdentity = EmbedVideoIdentity(
                mediaId = "https://cdn-a.example/episode.m3u8?token=former|1440000",
                generation = 1,
            ),
        )
        val activeEmbed = formerEmbed.copy(
            videoIdentity = EmbedVideoIdentity(
                mediaId = "https://cdn-b.example/episode.m3u8?token=current|1440000",
                generation = 2,
            ),
        )
        val formerIdentity = checkNotNull(formerEmbed.playbackProgressIdentity())
        val activeIdentity = checkNotNull(activeEmbed.playbackProgressIdentity())
        val activeTarget = ActivePlaybackTarget(
            animeId = playback.animeId,
            episodeNumber = playback.episodeNumber,
            generation = playback.sourceGeneration,
            mediaIds = setOf(activeEmbed.documentMediaId),
        ).scopedToActiveEmbedMedia(activeEmbed, playback)
        val events = mutableListOf<String>()

        assertNotEquals(formerIdentity.mediaInstanceId, activeIdentity.mediaInstanceId)
        assertNotEquals(formerIdentity.aniSkipSourceIdentity, activeIdentity.aniSkipSourceIdentity)
        assertFalse(acceptsPlaybackProgress(formerIdentity, activeTarget))
        assertTrue(acceptsPlaybackProgress(activeIdentity, activeTarget))
        flushProgressBeforeTransition(
            candidate = PlaybackProgressSnapshot(formerIdentity, 320_000L, 1_440_000L),
            confirmedIdentity = formerIdentity,
            activeTarget = activeTarget,
            persist = { events += "persist" },
            transition = { events += "invalidate" },
        )

        assertEquals(listOf("invalidate"), events)
    }
}
