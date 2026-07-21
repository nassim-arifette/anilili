package com.miruronative.ui.watch

private const val MIN_CONTENT_DURATION_MS = 120_000L
private const val MIN_PLAYING_SAMPLES = 3
private const val END_TOLERANCE_MS = 3_000L

internal data class EmbedEndSample(
    val positionMs: Long,
    val durationMs: Long,
    val observedPlayingSamples: Int,
)

/** A verified WebView end event, still tied to the logical playback that emitted it. */
data class EmbedPlaybackCompletion(
    val playbackKey: EmbedPlaybackKey,
    val reportedPositionMs: Long,
    val durationMs: Long,
    val observedPlayingSamples: Int,
)

internal data class EmbedCompletionCommit(
    val playbackKey: EmbedPlaybackKey,
    val positionMs: Long,
    val durationMs: Long,
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

/**
 * Revalidates a natural end at the state-owner boundary. The WebView navigation token protects
 * the bridge, while the playback key protects WatchViewModel after an episode/source transition.
 */
internal fun planEmbedCompletionCommit(
    completion: EmbedPlaybackCompletion,
    currentPlaybackKey: EmbedPlaybackKey,
    alreadyCommitted: Boolean,
): EmbedCompletionCommit? {
    if (alreadyCommitted || !acceptsEmbedPlaybackCallback(completion.playbackKey, currentPlaybackKey)) {
        return null
    }
    val sample = EmbedEndSample(
        positionMs = completion.reportedPositionMs,
        durationMs = completion.durationMs,
        observedPlayingSamples = completion.observedPlayingSamples,
    )
    if (!isLikelyEmbedContentEnd(sample)) return null
    return EmbedCompletionCommit(
        playbackKey = completion.playbackKey,
        positionMs = completion.durationMs,
        durationMs = completion.durationMs,
    )
}

/** Runs autoplay only after the terminal write has synchronously succeeded. */
internal fun finalizeEmbedCompletionThenNavigate(
    completion: EmbedPlaybackCompletion,
    shouldNavigate: Boolean,
    commit: (EmbedPlaybackCompletion) -> Boolean,
    navigate: () -> Unit,
): Boolean {
    val committed = commit(completion)
    if (committed && shouldNavigate) navigate()
    return committed
}

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
