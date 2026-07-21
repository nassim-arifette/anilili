package com.miruronative.ui.watch

import com.miruronative.data.model.AniSkipPlaybackSegment
import com.miruronative.data.model.AniSkipType
import com.miruronative.data.model.SkipTimes
import kotlin.math.roundToLong

internal enum class PlaybackSkipKind {
    OPENING,
    ENDING,
    MIXED_OPENING,
    MIXED_ENDING,
    RECAP,
}

internal enum class PlaybackSkipOrigin { ANISKIP, PROVIDER }

/** Lifecycle of the duration-bound lookup for the exact visible playback session. */
enum class AniSkipLookupStatus {
    AWAITING_DURATION,
    LOADING,
    COMPLETE,
}

internal data class PlaybackSkipMarker(
    val kind: PlaybackSkipKind,
    val origin: PlaybackSkipOrigin,
    val startMs: Long,
    val endMs: Long,
    val autoSkipEligible: Boolean,
) {
    fun contains(positionMs: Long): Boolean = positionMs >= startMs && positionMs < endMs
}

/** One deterministic marker per opening/ending family, plus AniSkip's independent recap marker. */
internal data class PlaybackSkipPlan(
    val opening: PlaybackSkipMarker?,
    val ending: PlaybackSkipMarker?,
    val recap: PlaybackSkipMarker?,
) {
    val automaticOpening: PlaybackSkipMarker?
        get() = opening?.takeIf { it.autoSkipEligible && !it.overlaps(recap) }

    val automaticEnding: PlaybackSkipMarker?
        get() = ending?.takeIf { it.autoSkipEligible && !it.overlaps(recap) }
}

internal data class PlaybackSkipAction(
    val label: String,
    val seekTargetMs: Long?,
    val advanceToNextEpisode: Boolean,
)

/**
 * AniSkip's typed semantic marker wins over an untyped provider range for the same family. This
 * is safety-critical for mixed openings/endings: retaining the provider range would silently
 * make content-bearing segments auto-skippable again. A provider range is only a fallback when
 * AniSkip has no valid marker for that family.
 */
internal fun buildPlaybackSkipPlan(
    providerSkip: SkipTimes?,
    aniSkipSegments: List<AniSkipPlaybackSegment>,
    aniSkipLookupStatus: AniSkipLookupStatus = AniSkipLookupStatus.COMPLETE,
): PlaybackSkipPlan {
    fun typedMarker(vararg priorities: AniSkipType): PlaybackSkipMarker? = priorities
        .asSequence()
        .mapNotNull { type -> aniSkipSegments.firstOrNull { it.type == type }?.toMarker() }
        .firstOrNull()

    val opening = typedMarker(AniSkipType.MIXED_OP, AniSkipType.OP)
        ?: providerOpeningMarker(
            providerSkip,
            autoSkipEligible = aniSkipLookupStatus == AniSkipLookupStatus.COMPLETE,
        )
    val ending = typedMarker(AniSkipType.MIXED_ED, AniSkipType.ED)
        ?: providerEndingMarker(
            providerSkip,
            autoSkipEligible = aniSkipLookupStatus == AniSkipLookupStatus.COMPLETE,
        )
    val recap = typedMarker(AniSkipType.RECAP)
    return PlaybackSkipPlan(opening = opening, ending = ending, recap = recap)
}

/**
 * Manual actions remain available for every segment. Recap wins only while it overlaps another
 * range because it is the more specific content label; mixed endings never become Next Episode.
 */
internal fun PlaybackSkipPlan.actionAt(
    positionMs: Long,
    hasNextEpisode: Boolean,
): PlaybackSkipAction? {
    val marker = listOfNotNull(recap, opening, ending).firstOrNull { it.contains(positionMs) }
        ?: return null
    return when (marker.kind) {
        PlaybackSkipKind.OPENING -> PlaybackSkipAction(
            label = "Skip Intro",
            seekTargetMs = marker.endMs,
            advanceToNextEpisode = false,
        )
        PlaybackSkipKind.ENDING -> if (hasNextEpisode) {
            PlaybackSkipAction(
                label = "Next Episode",
                seekTargetMs = null,
                advanceToNextEpisode = true,
            )
        } else {
            PlaybackSkipAction(
                label = "Skip Outro",
                seekTargetMs = marker.endMs,
                advanceToNextEpisode = false,
            )
        }
        PlaybackSkipKind.MIXED_OPENING -> PlaybackSkipAction(
            label = "Skip Mixed Opening",
            seekTargetMs = marker.endMs,
            advanceToNextEpisode = false,
        )
        PlaybackSkipKind.MIXED_ENDING -> PlaybackSkipAction(
            label = "Skip Mixed Ending",
            seekTargetMs = marker.endMs,
            advanceToNextEpisode = false,
        )
        PlaybackSkipKind.RECAP -> PlaybackSkipAction(
            label = "Skip Recap",
            seekTargetMs = marker.endMs,
            advanceToNextEpisode = false,
        )
    }
}

private fun AniSkipPlaybackSegment.toMarker(): PlaybackSkipMarker? {
    val startMs = interval.startSeconds.safeMilliseconds() ?: return null
    val endMs = interval.endSeconds.safeMilliseconds() ?: return null
    if (endMs <= startMs) return null
    val kind = when (type) {
        AniSkipType.OP -> PlaybackSkipKind.OPENING
        AniSkipType.ED -> PlaybackSkipKind.ENDING
        AniSkipType.MIXED_OP -> PlaybackSkipKind.MIXED_OPENING
        AniSkipType.MIXED_ED -> PlaybackSkipKind.MIXED_ENDING
        AniSkipType.RECAP -> PlaybackSkipKind.RECAP
    }
    return PlaybackSkipMarker(
        kind = kind,
        origin = PlaybackSkipOrigin.ANISKIP,
        startMs = startMs,
        endMs = endMs,
        autoSkipEligible = type == AniSkipType.OP || type == AniSkipType.ED,
    )
}

private fun providerOpeningMarker(
    skip: SkipTimes?,
    autoSkipEligible: Boolean,
): PlaybackSkipMarker? {
    val marker = skip ?: return null
    val endMs = marker.introEnd.safeMilliseconds() ?: return null
    val startMs = (marker.introStart ?: 0.0).safeMilliseconds() ?: return null
    return providerMarker(PlaybackSkipKind.OPENING, startMs, endMs, autoSkipEligible)
}

private fun providerEndingMarker(
    skip: SkipTimes?,
    autoSkipEligible: Boolean,
): PlaybackSkipMarker? {
    val marker = skip ?: return null
    val startMs = marker.outroStart.safeMilliseconds() ?: return null
    val endMs = marker.outroEnd.safeMilliseconds() ?: return null
    return providerMarker(PlaybackSkipKind.ENDING, startMs, endMs, autoSkipEligible)
}

private fun providerMarker(
    kind: PlaybackSkipKind,
    startMs: Long,
    endMs: Long,
    autoSkipEligible: Boolean,
): PlaybackSkipMarker? = if (endMs > startMs) {
    PlaybackSkipMarker(
        kind = kind,
        origin = PlaybackSkipOrigin.PROVIDER,
        startMs = startMs,
        endMs = endMs,
        autoSkipEligible = autoSkipEligible,
    )
} else {
    null
}

private fun PlaybackSkipMarker.overlaps(other: PlaybackSkipMarker?): Boolean =
    other != null && startMs < other.endMs && other.startMs < endMs

private fun Double?.safeMilliseconds(): Long? {
    val seconds = this ?: return null
    if (!seconds.isFinite() || seconds < 0.0 || seconds > Long.MAX_VALUE / 1_000.0) return null
    return (seconds * 1_000.0).roundToLong()
}
