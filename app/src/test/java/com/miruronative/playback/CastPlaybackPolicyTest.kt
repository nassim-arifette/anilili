package com.miruronative.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastPlaybackPolicyTest {
    @Test
    fun `remote disconnect resumes locally while player surface is active`() {
        assertEquals(
            CastTransferDirective.PRESERVE_PLAY_STATE,
            castTransferDirective(
                sourceRoute = PlaybackRoute.REMOTE,
                targetRoute = PlaybackRoute.LOCAL,
                hasLocalPlaybackOwner = true,
            ),
        )
    }

    @Test
    fun `late remote disconnect transfers locally in paused state after teardown`() {
        assertEquals(
            CastTransferDirective.TRANSFER_LOCAL_PAUSED,
            castTransferDirective(
                sourceRoute = PlaybackRoute.REMOTE,
                targetRoute = PlaybackRoute.LOCAL,
                hasLocalPlaybackOwner = false,
            ),
        )
    }

    @Test
    fun `connecting to Cast preserves play state without a local owner`() {
        assertEquals(
            CastTransferDirective.PRESERVE_PLAY_STATE,
            castTransferDirective(
                sourceRoute = PlaybackRoute.LOCAL,
                targetRoute = PlaybackRoute.REMOTE,
                hasLocalPlaybackOwner = false,
            ),
        )
    }

    @Test
    fun `overlapping player surfaces retain ownership until both are released`() {
        val owners = LocalPlaybackOwnerRegistry()
        val first = owners.acquire()
        val second = owners.acquire()

        assertTrue(owners.hasOwner())
        assertTrue(owners.release(first))
        assertTrue(owners.hasOwner())
        assertTrue(owners.release(second))
        assertFalse(owners.hasOwner())
    }

    @Test
    fun `releasing the same player surface twice is harmless`() {
        val owners = LocalPlaybackOwnerRegistry()
        val token = owners.acquire()

        assertTrue(owners.release(token))
        assertFalse(owners.release(token))
        assertFalse(owners.hasOwner())
    }

    @Test
    fun `only newest overlapping native surface may publish PiP geometry`() {
        val owners = LocalPlaybackOwnerRegistry()
        val outgoing = owners.acquire()
        val incoming = owners.acquire()

        assertFalse(owners.isLatest(outgoing))
        assertTrue(owners.isLatest(incoming))
        assertTrue(owners.release(incoming))
        assertTrue(owners.isLatest(outgoing))
    }

    @Test
    fun `paused remote item keeps service alive after task removal`() {
        assertFalse(
            shouldStopPlaybackServiceAfterTaskRemoved(
                playerInitialized = true,
                route = PlaybackRoute.REMOTE,
                mediaItemCount = 1,
                playWhenReady = false,
            ),
        )
    }

    @Test
    fun `paused local item may stop after task removal`() {
        assertTrue(
            shouldStopPlaybackServiceAfterTaskRemoved(
                playerInitialized = true,
                route = PlaybackRoute.LOCAL,
                mediaItemCount = 1,
                playWhenReady = false,
            ),
        )
    }

    @Test
    fun `Cast completion advances only the exact Watch episode and playback UUID`() {
        val active = EpisodeNavigatorPlaybackIdentity(
            animeId = 42,
            episodeNumber = 3.0,
            playbackGeneration = 7,
            playbackId = "active-playback",
        )
        val expected = RemotePlaybackHistoryIdentity(
            playbackId = "active-playback",
            animeId = 42,
            mediaId = "https://cdn/video.m3u8",
            episodeNumber = 3.0,
            generation = 7,
            watchOwnerGeneration = 12L,
        )

        assertTrue(acceptsEpisodeNavigatorPlayback(12L, active, expected))
        assertFalse(acceptsEpisodeNavigatorPlayback(11L, active, expected))
        assertFalse(
            acceptsEpisodeNavigatorPlayback(
                12L,
                active.copy(episodeNumber = 4.0),
                expected,
            ),
        )
        assertFalse(
            acceptsEpisodeNavigatorPlayback(
                12L,
                active.copy(playbackId = "stale-playback"),
                expected,
            ),
        )
    }

    @Test
    fun `navigator cleanup ignores the UUID bound after logical registration`() {
        val logical = EpisodeNavigatorPlaybackIdentity(42, 3.0, 7)
        val bound = logical.copy(playbackId = "cast-playback")

        assertTrue(bound.matchesLogicalPlayback(logical))
        assertFalse(bound.matchesLogicalPlayback(logical.copy(episodeNumber = 4.0)))
    }
}
