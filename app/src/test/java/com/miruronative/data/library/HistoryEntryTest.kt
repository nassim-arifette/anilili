package com.miruronative.data.library

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryEntryTest {
    @Test
    fun `legacy JSON defaults to the watched episode and saved position`() {
        val entry = Json.decodeFromString<HistoryEntry>(
            """
            {
              "anilistId": 42,
              "title": "Legacy show",
              "cover": null,
              "episodeNumber": 3.0,
              "provider": "allanime",
              "category": "sub",
              "positionMs": 45000,
              "durationMs": 90000
            }
            """.trimIndent(),
        )

        assertNull(entry.continuationEpisodeNumber)
        assertFalse(entry.hasContinuationTarget)
        assertEquals(3.0, entry.continueEpisodeNumber, 0.0)
        assertEquals("3", entry.continueEpisodeLabel)
        assertEquals(45_000L, entry.continuePositionMs)
        assertEquals(.5f, entry.continueProgressFraction)
        assertEquals(45_000L, entry.resumePositionFor(3.0))
    }

    @Test
    fun `completed episode keeps watched progress while continuation starts next episode at zero`() {
        val entry = historyEntry(
            episodeNumber = 3.0,
            positionMs = 90_000L,
            durationMs = 90_000L,
            continuationEpisodeNumber = 4.0,
        )

        assertEquals(3.0, entry.episodeNumber, 0.0)
        assertEquals(1f, entry.progressFraction)
        assertTrue(entry.belongsInContinueWatching)
        assertTrue(entry.hasContinuationTarget)
        assertEquals(4.0, entry.continueEpisodeNumber, 0.0)
        assertEquals("4", entry.continueEpisodeLabel)
        assertEquals(0L, entry.continuePositionMs)
        assertEquals(0L, entry.continueDurationMs)
        assertEquals(0f, entry.continueProgressFraction)
        assertNull(entry.resumePositionFor(3.0))
        assertEquals(0L, entry.resumePositionFor(4.0))
    }

    @Test
    fun `completed final series remains outside continue watching with no resume position`() {
        val entry = historyEntry(
            episodeNumber = 12.0,
            positionMs = 90_000L,
            durationMs = 90_000L,
            continuationEpisodeNumber = 13.0,
            completed = true,
        )

        assertFalse(entry.belongsInContinueWatching)
        assertFalse(entry.hasContinuationTarget)
        assertEquals(12.0, entry.continueEpisodeNumber, 0.0)
        assertNull(entry.resumePositionFor(12.0))
        assertNull(entry.resumePositionFor(13.0))
    }

    private fun historyEntry(
        episodeNumber: Double,
        positionMs: Long,
        durationMs: Long,
        continuationEpisodeNumber: Double? = null,
        completed: Boolean = false,
    ) = HistoryEntry(
        anilistId = 42,
        title = "Test show",
        cover = null,
        episodeNumber = episodeNumber,
        episodeTitle = "Episode title",
        provider = "allanime",
        category = "sub",
        positionMs = positionMs,
        durationMs = durationMs,
        continuationEpisodeNumber = continuationEpisodeNumber,
        completed = completed,
    )
}
