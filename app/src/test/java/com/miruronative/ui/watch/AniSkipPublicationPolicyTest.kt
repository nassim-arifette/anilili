package com.miruronative.ui.watch

import com.miruronative.data.model.Category
import com.miruronative.data.model.SkipTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AniSkipPublicationPolicyTest {
    private val request = PlaybackRequestToken(sessionGeneration = 4, requestGeneration = 12)

    @Test
    fun `duration callbacks are grouped to stable one second buckets`() {
        assertEquals(1_440_000L, aniSkipDurationBucketMs(1_439_501L))
        assertEquals(1_440_000L, aniSkipDurationBucketMs(1_440_499L))
        assertNull(aniSkipDurationBucketMs(1L))
        assertNull(aniSkipDurationBucketMs(0L))
        assertNull(aniSkipDurationBucketMs(Long.MAX_VALUE))
    }

    @Test
    fun `typed marker publication is bound to request generation media and duration`() {
        val expected = AniSkipLookupIdentity(
            request = request,
            animeId = 21,
            episodeNumber = 7.5,
            provider = "allanime",
            category = Category.SUB,
            sourceGeneration = 30,
            mediaId = "https://cdn.example/episode-7-5.m3u8",
            durationBucketMs = 1_440_000L,
            mediaInstanceId = "embed:4:1",
        )

        assertEquals(true, canPublishAniSkipSegments(expected, expected))
        assertEquals(false, canPublishAniSkipSegments(expected, expected.copy(episodeNumber = 8.0)))
        assertEquals(
            false,
            canPublishAniSkipSegments(expected, expected.copy(mediaId = "https://cdn.example/other.m3u8")),
        )
        assertEquals(
            false,
            canPublishAniSkipSegments(expected, expected.copy(durationBucketMs = 1_441_000L)),
        )
        assertEquals(
            false,
            canPublishAniSkipSegments(
                expected,
                expected.copy(mediaInstanceId = "embed:4:2"),
            ),
        )
        assertEquals(
            false,
            canPublishAniSkipSegments(
                expected,
                expected.copy(request = request.copy(requestGeneration = 13)),
            ),
        )
        assertFalse(expected.toString().contains("cdn.example"))
    }

    @Test
    fun `zero duration tick during lookup retains publication and releases provider fallback`() {
        val playbackIdentity = PlaybackIdentity(
            animeId = 21,
            episodeNumber = 7.5,
            generation = 30,
            mediaId = "https://cdn.example/episode-7-5.m3u8",
        )
        val measuredDurationMs = 1_439_840L
        var progress = progressSnapshotRetainingValidDuration(
            previous = null,
            identity = playbackIdentity,
            positionMs = 12_000L,
            durationMs = measuredDurationMs,
        )
        val expected = AniSkipLookupIdentity(
            request = request,
            animeId = playbackIdentity.animeId,
            episodeNumber = playbackIdentity.episodeNumber,
            provider = "allanime",
            category = Category.SUB,
            sourceGeneration = playbackIdentity.generation,
            mediaId = playbackIdentity.mediaId,
            durationBucketMs = checkNotNull(aniSkipDurationBucketMs(progress.durationMs)),
        )
        val providerSkip = SkipTimes(0.0, 90.0, null, null)

        val loadingPlan = buildPlaybackSkipPlan(
            providerSkip = providerSkip,
            aniSkipSegments = emptyList(),
            aniSkipLookupStatus = AniSkipLookupStatus.LOADING,
        )
        assertNull(loadingPlan.automaticOpening)
        assertFalse(checkNotNull(loadingPlan.opening).autoSkipEligible)

        // Exact regression interleaving: measured tick -> request starts -> duration=0 tick ->
        // response publishes. The transient zero must not erase the request's duration evidence.
        progress = progressSnapshotRetainingValidDuration(
            previous = progress,
            identity = playbackIdentity,
            positionMs = 13_000L,
            durationMs = 0L,
        )
        assertEquals(measuredDurationMs, progress.durationMs)
        val current = expected.copy(
            durationBucketMs = checkNotNull(aniSkipDurationBucketMs(progress.durationMs)),
        )
        assertTrue(canPublishAniSkipSegments(expected, current))

        val completedPlan = buildPlaybackSkipPlan(
            providerSkip = providerSkip,
            aniSkipSegments = emptyList(),
            aniSkipLookupStatus = AniSkipLookupStatus.COMPLETE,
        )
        assertEquals(PlaybackSkipOrigin.PROVIDER, completedPlan.automaticOpening?.origin)
        assertTrue(checkNotNull(completedPlan.opening).autoSkipEligible)
    }

    @Test
    fun `unknown duration never inherits from another concrete media identity`() {
        val previousIdentity = PlaybackIdentity(21, 7.5, 30, "https://cdn.example/first.m3u8")
        val previous = PlaybackProgressSnapshot(previousIdentity, 12_000L, 1_439_840L)

        val nextSource = progressSnapshotRetainingValidDuration(
            previous = previous,
            identity = previousIdentity.copy(mediaId = "https://cdn.example/second.m3u8"),
            positionMs = 0L,
            durationMs = 0L,
        )
        val nextGeneration = progressSnapshotRetainingValidDuration(
            previous = previous,
            identity = previousIdentity.copy(generation = 31),
            positionMs = 0L,
            durationMs = 0L,
        )

        assertEquals(0L, nextSource.durationMs)
        assertEquals(0L, nextGeneration.durationMs)
        assertNull(aniSkipDurationBucketMs(nextSource.durationMs))
        assertNull(aniSkipDurationBucketMs(nextGeneration.durationMs))
    }
}
