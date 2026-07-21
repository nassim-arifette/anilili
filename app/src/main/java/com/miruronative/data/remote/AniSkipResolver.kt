package com.miruronative.data.remote

import com.miruronative.data.model.AniSkipEpisodeReference
import com.miruronative.data.model.AniSkipInterval
import com.miruronative.data.model.AniSkipPlaybackSegment
import com.miruronative.data.model.AniSkipRelationRule
import com.miruronative.data.model.AniSkipSegment
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.math.abs

/** Stable decimal spelling for AniSkip route parameters and cache keys (including episode zero). */
internal fun canonicalAniSkipEpisode(episode: Double): String {
    require(episode.isFinite() && episode >= 0.0) { "Episode must be a finite non-negative number" }
    return BigDecimal.valueOf(episode).stripTrailingZeros().toPlainString()
}

/**
 * Applies the first valid AniSkip relation rule that contains [episode].
 *
 * A missing `from.end` is open-ended. Offsets are retained, so fractional episode numbers such as
 * 12.5 map to 1.5 instead of being truncated to an unrelated episode.
 */
internal fun resolveAniSkipEpisode(
    malId: Int,
    episode: Double,
    rules: List<AniSkipRelationRule>,
): AniSkipEpisodeReference {
    require(malId > 0) { "MAL id must be positive" }
    require(episode.isFinite() && episode >= 0.0) { "Episode must be a finite non-negative number" }
    rules.forEach { rule ->
        val fromStart = rule.from.start
        val fromEnd = rule.from.end
        val toStart = rule.to.start
        val toEnd = rule.to.end
        if (fromStart < 0 || fromEnd != null && fromEnd < fromStart) return@forEach
        if (rule.to.malId <= 0 || toStart < 0 || toEnd != null && toEnd < toStart) return@forEach
        if (episode < fromStart || fromEnd != null && episode > fromEnd) return@forEach

        val mappedEpisode = toStart.toDouble() + (episode - fromStart.toDouble())
        if (!mappedEpisode.isFinite() || mappedEpisode < 0.0) return@forEach
        if (toEnd != null && mappedEpisode > toEnd) return@forEach
        return AniSkipEpisodeReference(malId = rule.to.malId, episode = mappedEpisode)
    }
    return AniSkipEpisodeReference(malId = malId, episode = episode)
}

/**
 * Moves AniSkip's reference intervals onto the active encode's timeline.
 *
 * AniSkip selects candidates by approximate duration, but the response still needs local
 * validation. Contributions whose reference cut differs by more than 20 seconds are ignored.
 * Shifted endpoints are clamped to the media duration and collapsed/invalid ranges are dropped.
 */
internal fun resolveAniSkipSegments(
    segments: List<AniSkipSegment>,
    actualDurationSeconds: Double,
): List<AniSkipPlaybackSegment> {
    if (!actualDurationSeconds.isFinite() || actualDurationSeconds <= 0.0) return emptyList()
    return segments.mapNotNull { source ->
        val referenceDuration = source.referenceDurationSeconds
        if (!referenceDuration.isFinite() || referenceDuration <= 0.0) return@mapNotNull null
        val durationOffset = actualDurationSeconds - referenceDuration
        if (abs(durationOffset) > ANI_SKIP_DURATION_TOLERANCE_SECONDS) return@mapNotNull null

        val rawStart = source.interval.startSeconds
        val rawEnd = source.interval.endSeconds
        if (!rawStart.isFinite() || !rawEnd.isFinite() || rawStart < 0.0 || rawEnd <= rawStart) {
            return@mapNotNull null
        }
        val adjustedStart = (rawStart + durationOffset).coerceIn(0.0, actualDurationSeconds)
        val adjustedEnd = (rawEnd + durationOffset).coerceIn(0.0, actualDurationSeconds)
        if (adjustedEnd <= adjustedStart) return@mapNotNull null
        AniSkipPlaybackSegment(
            source = source,
            interval = AniSkipInterval(
                startSeconds = adjustedStart,
                endSeconds = adjustedEnd,
            ),
        )
    }
}

/** Cache identity is encode-scoped without persisting a potentially signed media URL. */
internal fun aniSkipSegmentsCacheKey(
    malId: Int,
    episode: Double,
    actualDurationMs: Long,
    sourceIdentity: String,
): String {
    require(malId > 0) { "MAL id must be positive" }
    require(actualDurationMs > 0L) { "A positive media duration is required" }
    require(sourceIdentity.isNotBlank()) { "A concrete media source identity is required" }
    val sourceHash = MessageDigest.getInstance("SHA-256")
        .digest(sourceIdentity.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(radix = 16).padStart(length = 2, padChar = '0')
        }
    return "aniskip:v2:segments:$malId:${canonicalAniSkipEpisode(episode)}:$actualDurationMs:$sourceHash"
}

internal const val ANI_SKIP_DURATION_TOLERANCE_SECONDS = 20.0
