package com.miruronative.ui.watch

private const val MIN_CONTENT_DURATION_MS = 120_000L
private const val MIN_PLAYING_SAMPLES = 3
private const val END_TOLERANCE_MS = 3_000L

internal data class EmbedEndSample(
    val positionMs: Long,
    val durationMs: Long,
    val observedPlayingSamples: Int,
)

internal fun embedEndSampleFromSeconds(
    positionSec: Double,
    durationSec: Double,
    observedPlayingSamples: Int,
): EmbedEndSample? {
    if (!positionSec.isFinite() || !durationSec.isFinite()) return null
    if (positionSec < 0.0 || durationSec <= 0.0 || observedPlayingSamples < 0) return null
    val maxSeconds = Long.MAX_VALUE / 1_000.0
    if (positionSec > maxSeconds || durationSec > maxSeconds) return null
    return EmbedEndSample(
        positionMs = (positionSec * 1_000.0).toLong(),
        durationMs = (durationSec * 1_000.0).toLong(),
        observedPlayingSamples = observedPlayingSamples,
    )
}

/**
 * A Web page may expose a short preroll as its first video element. Natural completion is used
 * for episode navigation only after observing a plausible content-length video playing for
 * several polls and reaching its real end. False negatives are safer than skipping an episode.
 */
internal fun isLikelyEmbedContentEnd(sample: EmbedEndSample): Boolean =
    sample.durationMs >= MIN_CONTENT_DURATION_MS &&
        sample.observedPlayingSamples >= MIN_PLAYING_SAMPLES &&
        sample.positionMs >= sample.durationMs - END_TOLERANCE_MS &&
        sample.positionMs <= sample.durationMs + END_TOLERANCE_MS

/** Exactly-once gate shared by metadata-driven outro and natural completion. */
internal class EmbedAutoAdvanceGate {
    private var handledNavigationToken: String? = null

    fun tryAdvance(
        navigationToken: String,
        autoplay: Boolean,
        hasNextEpisode: Boolean,
    ): Boolean {
        if (navigationToken.isBlank() || !autoplay || !hasNextEpisode) return false
        if (handledNavigationToken == navigationToken) return false
        handledNavigationToken = navigationToken
        return true
    }
}
