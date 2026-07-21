package com.miruronative.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedControlReadinessPolicyTest {
    private val playbackKey = EmbedPlaybackKey(
        animeId = 21,
        provider = "provider-a",
        category = "sub",
        episodeNumber = 12.0,
        sourceGeneration = 4,
    )
    private val selectedVideo = EmbedVideoIdentity(
        mediaId = "https://cdn.example/video-1080.m3u8",
        generation = 3L,
    )
    private val activeMedia = EmbedMediaIdentity(
        playbackKey = playbackKey,
        navigationGeneration = 7L,
        documentMediaId = "https://provider.example/embed/12",
        videoIdentity = selectedVideo,
    )

    @Test
    fun `video announcement without concrete active identity keeps managed controls unavailable`() {
        assertFalse(
            canUseManagedEmbedControls(
                managedControlsDeclared = true,
                bridgePlaybackAvailable = true,
                activeMediaIdentity = null,
                reportedMediaIdentity = null,
            ),
        )
    }

    @Test
    fun `mismatched bridge identity keeps managed controls unavailable`() {
        assertFalse(
            canUseManagedEmbedControls(
                managedControlsDeclared = true,
                bridgePlaybackAvailable = true,
                activeMediaIdentity = activeMedia,
                reportedMediaIdentity = selectedVideo.copy(generation = 2L),
            ),
        )
    }

    @Test
    fun `exact concrete bridge identity enables managed controls`() {
        assertTrue(
            canUseManagedEmbedControls(
                managedControlsDeclared = true,
                bridgePlaybackAvailable = true,
                activeMediaIdentity = activeMedia,
                reportedMediaIdentity = selectedVideo,
            ),
        )
    }
}
