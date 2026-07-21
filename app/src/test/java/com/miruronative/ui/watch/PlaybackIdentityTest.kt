package com.miruronative.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackIdentityTest {

    private val active = ActivePlaybackTarget(
        animeId = 101,
        episodeNumber = 6.0,
        generation = 12,
        mediaIds = setOf("https://cdn.example/episode-6.m3u8", "https://cdn.example/episode-6-720.m3u8"),
    )

    @Test
    fun `current media item progress is accepted`() {
        assertTrue(
            acceptsPlaybackProgress(
                PlaybackIdentity(101, 6.0, 12, "https://cdn.example/episode-6.m3u8"),
                active,
            ),
        )
    }

    @Test
    fun `previous episode callback is rejected during transition`() {
        assertFalse(
            acceptsPlaybackProgress(
                PlaybackIdentity(101, 5.0, 11, "https://cdn.example/episode-5.m3u8"),
                active,
            ),
        )
    }

    @Test
    fun `previous anime callback is rejected even for same episode number`() {
        assertFalse(
            acceptsPlaybackProgress(
                PlaybackIdentity(77, 6.0, 12, "https://cdn.example/episode-6.m3u8"),
                active,
            ),
        )
    }

    @Test
    fun `old generation is rejected when source changes on same episode`() {
        assertFalse(
            acceptsPlaybackProgress(
                PlaybackIdentity(101, 6.0, 11, "https://cdn.example/episode-6.m3u8"),
                active,
            ),
        )
    }

    @Test
    fun `media item outside current source inventory is rejected`() {
        assertFalse(
            acceptsPlaybackProgress(
                PlaybackIdentity(101, 6.0, 12, "https://old.example/episode-6.m3u8"),
                active,
            ),
        )
    }

    @Test
    fun `quality media ids share the logical playback session`() {
        val auto = PlaybackIdentity(101, 6.0, 12, "https://cdn.example/episode-6.m3u8")
        val manual720 = PlaybackIdentity(101, 6.0, 12, "https://cdn.example/episode-6-720.m3u8")

        assertTrue(acceptsPlaybackProgress(manual720, active))
        assertTrue(isSamePlaybackSession(auto, manual720))
        assertFalse(isSamePlaybackSession(auto, manual720.copy(generation = 13)))
    }

    @Test
    fun `first playing callback confirms history`() {
        val current = PlaybackIdentity(101, 6.0, 12, "https://cdn.example/episode-6.m3u8")

        assertTrue(isNewConfirmedPlayback(null, current))
        assertFalse(isNewConfirmedPlayback(current, current.copy(mediaId = "https://cdn.example/720.m3u8")))
    }

    @Test
    fun `new episode or retry generation requires a new confirmation`() {
        val current = PlaybackIdentity(101, 6.0, 12, "https://cdn.example/episode-6.m3u8")

        assertTrue(isNewConfirmedPlayback(current, current.copy(episodeNumber = 7.0)))
        assertTrue(isNewConfirmedPlayback(current, current.copy(generation = 13)))
    }
}
