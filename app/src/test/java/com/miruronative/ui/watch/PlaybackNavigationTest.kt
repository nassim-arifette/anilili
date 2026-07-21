package com.miruronative.ui.watch

import com.miruronative.data.model.Category
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackNavigationTest {
    private val episodeOne = PlaybackNavigationIdentity(
        animeId = 100,
        episodeNumber = 1.0,
        provider = "bonk",
        category = Category.SUB,
        playbackGeneration = 2,
        streamUrl = "https://cdn.example/episode-1.m3u8",
    )

    @Test
    fun `only the current player may navigate episodes`() {
        assertTrue(isCurrentPlaybackNavigation(episodeOne, episodeOne))
        assertFalse(
            isCurrentPlaybackNavigation(
                episodeOne,
                episodeOne.copy(episodeNumber = 2.0, streamUrl = "https://cdn.example/episode-2.m3u8"),
            ),
        )
        assertFalse(
            isCurrentPlaybackNavigation(
                episodeOne,
                episodeOne.copy(playbackGeneration = 3),
            ),
        )
    }

    @Test
    fun `same pending episode resolution is idempotent`() {
        val request = resolution(episode = 2.0)

        assertTrue(isDuplicateEpisodeResolution(true, request, request))
        assertFalse(isDuplicateEpisodeResolution(false, request, request))
    }

    @Test
    fun `different episode or fallback route can replace pending resolution`() {
        val request = resolution(episode = 2.0)

        assertFalse(isDuplicateEpisodeResolution(true, request, resolution(episode = 3.0)))
        assertFalse(
            isDuplicateEpisodeResolution(
                true,
                request,
                request.copy(excludedProviders = setOf("bonk")),
            ),
        )
    }

    private fun resolution(episode: Double) = EpisodeResolutionKey(
        animeId = 100,
        episodeNumber = episode,
        preferredProvider = "bonk",
        category = Category.SUB,
        excludedProviders = emptySet(),
    )
}
