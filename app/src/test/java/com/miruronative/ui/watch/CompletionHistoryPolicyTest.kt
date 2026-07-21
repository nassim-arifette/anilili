package com.miruronative.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionHistoryPolicyTest {
    @Test
    fun `latest released episode of an airing series stays in continue watching`() {
        assertFalse(
            isConfirmedFinalSeriesEpisode(
                episodeNumber = 5.0,
                totalEpisodes = 12,
                hasNextEpisode = false,
            ),
        )
    }

    @Test
    fun `known final episode is retired after natural completion`() {
        assertTrue(
            isConfirmedFinalSeriesEpisode(
                episodeNumber = 12.0,
                totalEpisodes = 12,
                hasNextEpisode = false,
            ),
        )
    }

    @Test
    fun `unknown series total is never treated as proven completion`() {
        assertFalse(
            isConfirmedFinalSeriesEpisode(
                episodeNumber = 12.0,
                totalEpisodes = null,
                hasNextEpisode = false,
            ),
        )
    }

    @Test
    fun `fractional specials cannot complete the numbered series`() {
        assertFalse(
            isConfirmedFinalSeriesEpisode(
                episodeNumber = 12.5,
                totalEpisodes = 12,
                hasNextEpisode = false,
            ),
        )
    }

    @Test
    fun `navigable next episode always prevents retirement`() {
        assertFalse(
            isConfirmedFinalSeriesEpisode(
                episodeNumber = 12.0,
                totalEpisodes = 12,
                hasNextEpisode = true,
            ),
        )
    }
}
