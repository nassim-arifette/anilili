package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun `managed embed target requires its exact concrete media instance`() {
        val concrete = PlaybackIdentity(
            animeId = 101,
            episodeNumber = 6.0,
            generation = 12,
            mediaId = "https://cdn.example/episode-6.m3u8",
            mediaInstanceId = "embed:40:2",
            aniSkipSourceIdentity = "embed-sha256:current",
        )
        val embedTarget = active.copy(allowedMediaInstanceIds = setOf("embed:40:2"))

        assertTrue(acceptsPlaybackProgress(concrete, embedTarget))
        assertFalse(
            acceptsPlaybackProgress(
                concrete.copy(
                    mediaInstanceId = "embed:40:1",
                    aniSkipSourceIdentity = "embed-sha256:former",
                ),
                embedTarget,
            ),
        )
        assertFalse(acceptsPlaybackProgress(concrete, embedTarget.copy(allowedMediaInstanceIds = emptySet())))
    }

    @Test
    fun `native error requires matching callback identity and media id`() {
        val current = PlaybackIdentity(101, 6.0, 12, "https://cdn.example/episode-6.m3u8")

        assertTrue(acceptsNativePlaybackError(current, current.mediaId, active))
        assertFalse(acceptsNativePlaybackError(current, "https://old.example/video.m3u8", active))
        assertFalse(acceptsNativePlaybackError(current.copy(generation = 11), current.mediaId, active))
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
    fun `episode state resets even when a provider reuses the same URL`() {
        val episodeSix = PlaybackIdentity(101, 6.0, 12, "https://cdn.example/shared.m3u8")
        val episodeSeven = episodeSix.copy(episodeNumber = 7.0, generation = 13)

        assertNotEquals(episodeSix.nativePlaybackSessionKey(), episodeSeven.nativePlaybackSessionKey())
    }

    @Test
    fun `quality changes retain episode local state`() {
        val auto = PlaybackIdentity(101, 6.0, 12, "https://cdn.example/auto.m3u8")
        val manual = auto.copy(mediaId = "https://cdn.example/720.m3u8")

        assertEquals(auto.nativePlaybackSessionKey(), manual.nativePlaybackSessionKey())
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

    @Test
    fun `signed media and AniSkip identities are redacted from diagnostics`() {
        val signedMedia = "https://embed.example/watch?signature=document-secret"
        val aniSkipIdentity = "embed-sha256:private-cache-scope"
        val identity = PlaybackIdentity(
            animeId = 101,
            episodeNumber = 6.0,
            generation = 12,
            mediaId = signedMedia,
            mediaInstanceId = "embed:40:2",
            aniSkipSourceIdentity = aniSkipIdentity,
        )
        val diagnostic = identity.toString()

        assertFalse(diagnostic.contains(signedMedia))
        assertFalse(diagnostic.contains("document-secret"))
        assertFalse(diagnostic.contains(aniSkipIdentity))
        assertEquals(aniSkipIdentity, identity.sourceIdentityForAniSkipLookup())
        assertEquals(
            signedMedia,
            identity.copy(aniSkipSourceIdentity = null).sourceIdentityForAniSkipLookup(),
        )
    }
}
