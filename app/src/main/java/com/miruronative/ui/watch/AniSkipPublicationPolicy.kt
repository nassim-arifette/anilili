package com.miruronative.ui.watch

import com.miruronative.data.model.Category
import com.miruronative.data.model.SkipTimes

/** Exact playback that may receive a delayed AniSkip result. */
internal data class AniSkipPublicationIdentity(
    val request: PlaybackRequestToken,
    val animeId: Int,
    val episodeNumber: Double,
    val provider: String,
    val category: Category,
    val sourceGeneration: Int,
)

/**
 * Returns a marker update only for the playback that launched the lookup. Existing valid provider
 * ranges always win; AniSkip may fill a missing intro or outro but never replace either one.
 */
internal fun lateAniSkipUpdate(
    expected: AniSkipPublicationIdentity,
    current: AniSkipPublicationIdentity,
    currentSkip: SkipTimes?,
    aniSkip: SkipTimes?,
): SkipTimes? {
    if (expected != current) return null
    val merged = mergeSkipTimes(currentSkip, aniSkip)
    return merged?.takeUnless { it == currentSkip }
}
