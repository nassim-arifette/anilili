package com.miruronative.ui.watch

import com.miruronative.data.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
                expected.copy(request = request.copy(requestGeneration = 13)),
            ),
        )
    }
}
