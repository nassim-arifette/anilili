package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `natural non-final completion targets the catalog next episode`() {
        assertEquals(
            6.0,
            continuationEpisodeAfterNaturalEnd(
                currentEpisodeNumber = 5.0,
                nextEpisodeNumber = 6.0,
                completedFinalEpisode = false,
            )!!,
            0.0,
        )
    }

    @Test
    fun `fractional catalog episode remains the exact continuation target`() {
        assertEquals(
            6.5,
            continuationEpisodeAfterNaturalEnd(
                currentEpisodeNumber = 6.0,
                nextEpisodeNumber = 6.5,
                completedFinalEpisode = false,
            )!!,
            0.0,
        )
    }

    @Test
    fun `final series and missing next episode have no continuation target`() {
        assertNull(
            continuationEpisodeAfterNaturalEnd(
                currentEpisodeNumber = 12.0,
                nextEpisodeNumber = 13.0,
                completedFinalEpisode = true,
            ),
        )
        assertNull(
            continuationEpisodeAfterNaturalEnd(
                currentEpisodeNumber = 5.0,
                nextEpisodeNumber = null,
                completedFinalEpisode = false,
            ),
        )
    }
}
