package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCompletionPolicyTest {
    private val identity = NativePlaybackIdentity(
        playbackId = "playback-a",
        animeId = 42,
        mediaId = "https://cdn.example/episode-3.m3u8",
        episodeNumber = 3.0,
    )
    private val availableMediaIds = setOf(identity.mediaId)

    @Test
    fun `terminal event requires the exact media item to have played`() {
        assertFalse(isConfirmedNativeTerminalEvent(identity, null))
        assertFalse(isConfirmedNativeTerminalEvent(identity, identity.copy(playbackId = "replacement")))
        assertTrue(isConfirmedNativeTerminalEvent(identity, identity))
    }

    @Test
    fun `valid ended event commits the terminal duration`() {
        val commit = planNativeCompletionCommit(
            completion = NativePlaybackCompletion(identity, reportedPositionMs = 1_439_400L, durationMs = 1_440_000L),
            activeIdentity = identity,
            currentAnimeId = 42,
            currentEpisodeNumber = 3.0,
            availableMediaIds = availableMediaIds,
            alreadyCommitted = false,
        )

        assertEquals(
            NativeCompletionCommit(identity, positionMs = 1_440_000L, durationMs = 1_440_000L),
            commit,
        )
    }

    @Test
    fun `late end from the previous episode is rejected`() {
        val completion = NativePlaybackCompletion(identity, reportedPositionMs = 1_440_000L, durationMs = 1_440_000L)

        assertNull(
            planNativeCompletionCommit(
                completion = completion,
                activeIdentity = identity,
                currentAnimeId = 42,
                currentEpisodeNumber = 4.0,
                availableMediaIds = availableMediaIds,
                alreadyCommitted = false,
            ),
        )
    }

    @Test
    fun `end from a replaced media item instance is rejected`() {
        val replacement = identity.copy(playbackId = "playback-b")

        assertNull(
            planNativeCompletionCommit(
                completion = NativePlaybackCompletion(identity, 1_440_000L, 1_440_000L),
                activeIdentity = replacement,
                currentAnimeId = 42,
                currentEpisodeNumber = 3.0,
                availableMediaIds = availableMediaIds,
                alreadyCommitted = false,
            ),
        )
    }

    @Test
    fun `duplicate and invalid terminal samples are rejected`() {
        val valid = NativePlaybackCompletion(identity, 1_440_000L, 1_440_000L)
        assertNull(
            planNativeCompletionCommit(valid, identity, 42, 3.0, availableMediaIds, alreadyCommitted = true),
        )
        assertNull(
            planNativeCompletionCommit(valid.copy(durationMs = 0L), identity, 42, 3.0, availableMediaIds, false),
        )
        assertNull(
            planNativeCompletionCommit(valid.copy(reportedPositionMs = -1L), identity, 42, 3.0, availableMediaIds, false),
        )
    }

    @Test
    fun `identity requires the exact anime episode and one of its media ids`() {
        assertTrue(isCurrentNativePlaybackIdentity(identity, 42, 3.0, availableMediaIds))
        assertEquals(false, isCurrentNativePlaybackIdentity(identity, 99, 3.0, availableMediaIds))
        assertEquals(false, isCurrentNativePlaybackIdentity(identity, 42, 3.5, availableMediaIds))
        assertEquals(false, isCurrentNativePlaybackIdentity(identity, 42, 3.0, emptySet()))
        assertEquals(false, isCurrentNativePlaybackIdentity(identity.copy(playbackId = ""), 42, 3.0, availableMediaIds))
    }

    @Test
    fun `terminal commit completes before autoplay navigation`() {
        val calls = mutableListOf<String>()
        val completion = NativePlaybackCompletion(identity, 1_439_400L, 1_440_000L)

        val committed = finalizeNativeCompletionThenNavigate(
            completion = completion,
            shouldNavigate = true,
            commit = {
                calls += "commit"
                true
            },
            navigate = { calls += "next" },
        )

        assertTrue(committed)
        assertEquals(listOf("commit", "next"), calls)
    }

    @Test
    fun `duplicate ended event writes and advances only once`() {
        val completion = NativePlaybackCompletion(identity, 1_440_000L, 1_440_000L)
        var committedIdentity: NativePlaybackIdentity? = null
        var writes = 0
        var advances = 0
        val commit: (NativePlaybackCompletion) -> Boolean = { event ->
            val planned = planNativeCompletionCommit(
                completion = event,
                activeIdentity = identity,
                currentAnimeId = 42,
                currentEpisodeNumber = 3.0,
                availableMediaIds = availableMediaIds,
                alreadyCommitted = committedIdentity == event.identity,
            )
            if (planned == null) {
                false
            } else {
                writes++
                committedIdentity = planned.identity
                true
            }
        }

        repeat(2) {
            finalizeNativeCompletionThenNavigate(
                completion = completion,
                shouldNavigate = true,
                commit = commit,
                navigate = { advances++ },
            )
        }

        assertEquals(1, writes)
        assertEquals(1, advances)
    }

    @Test
    fun `rejected late end never starts autoplay`() {
        var advances = 0
        val lateCompletion = NativePlaybackCompletion(identity, 1_440_000L, 1_440_000L)

        val committed = finalizeNativeCompletionThenNavigate(
            completion = lateCompletion,
            shouldNavigate = true,
            commit = { event ->
                planNativeCompletionCommit(
                    completion = event,
                    activeIdentity = identity.copy(playbackId = "playback-b", episodeNumber = 4.0),
                    currentAnimeId = 42,
                    currentEpisodeNumber = 4.0,
                    availableMediaIds = setOf("https://cdn.example/episode-4.m3u8"),
                    alreadyCommitted = false,
                ) != null
            },
            navigate = { advances++ },
        )

        assertEquals(false, committed)
        assertEquals(0, advances)
    }
}
