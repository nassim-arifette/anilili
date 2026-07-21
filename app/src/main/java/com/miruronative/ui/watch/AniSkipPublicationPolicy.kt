package com.miruronative.ui.watch

import com.miruronative.data.model.Category

/** Exact duration-bearing media session allowed to receive an AniSkip v2 response. */
internal data class AniSkipLookupIdentity(
    val request: PlaybackRequestToken,
    val animeId: Int,
    val episodeNumber: Double,
    val provider: String,
    val category: Category,
    val sourceGeneration: Int,
    val mediaId: String,
    val durationBucketMs: Long,
)

/**
 * AniSkip works in seconds; grouping stable player duration callbacks to one-second precision
 * prevents duplicate lookups caused by sub-second container jitter while retaining the real
 * duration used for timestamp correction.
 */
internal fun aniSkipDurationBucketMs(durationMs: Long): Long? {
    if (durationMs <= 0L || durationMs > Long.MAX_VALUE - 500L) return null
    return (((durationMs + 500L) / 1_000L) * 1_000L).takeIf { it > 0L }
}

internal fun canPublishAniSkipSegments(
    expected: AniSkipLookupIdentity,
    current: AniSkipLookupIdentity,
): Boolean = expected == current
