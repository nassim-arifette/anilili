package com.miruronative.ui.watch

/** A native stream that can replace the currently failing decoder input. */
internal data class DecoderStreamCandidate(
    val mediaId: String,
    val height: Int?,
)

/** The next recovery step for a decoder failure. */
internal sealed interface DecoderFallbackAction {
    val resumePositionMs: Long

    data class SwitchStream(
        val mediaId: String,
        val height: Int,
        override val resumePositionMs: Long,
    ) : DecoderFallbackAction

    data class RetryCurrentStreamCapped(
        val mediaId: String,
        val maxHeight: Int,
        override val resumePositionMs: Long,
    ) : DecoderFallbackAction

    data class Exhausted(
        val attemptedMediaIds: Set<String>,
        override val resumePositionMs: Long,
    ) : DecoderFallbackAction

    data class IgnoreStaleSession(
        override val resumePositionMs: Long,
    ) : DecoderFallbackAction
}

/**
 * Plans decoder recovery inside one logical episode/source generation.
 *
 * Distinct lower-resolution URLs are tried from nearest to furthest resolution before the
 * current manifest gets one track-capped retry. Every installed fallback URL is marked attempted
 * immediately, so a late/duplicate failure can never send recovery back around A -> B -> A.
 */
internal class PlayerDecoderRetryPolicy(
    private val cappedHeight: Int = 720,
) {
    private var session: NativePlaybackSessionKey? = null
    private val attemptedMediaIds = linkedSetOf<String>()
    private val cappedMediaIds = mutableSetOf<String>()

    fun startSession(session: NativePlaybackSessionKey) {
        if (this.session == session) return
        this.session = session
        attemptedMediaIds.clear()
        cappedMediaIds.clear()
    }

    fun onDecoderFailure(
        session: NativePlaybackSessionKey,
        failedMediaId: String?,
        failedHeight: Int?,
        candidates: List<DecoderStreamCandidate>,
        positionMs: Long,
    ): DecoderFallbackAction {
        val resumePositionMs = positionMs.coerceAtLeast(0L)
        if (session != this.session) {
            return DecoderFallbackAction.IgnoreStaleSession(resumePositionMs)
        }

        val mediaId = failedMediaId?.takeIf(String::isNotBlank)
            ?: return DecoderFallbackAction.Exhausted(attemptedMediaIds.toSet(), resumePositionMs)
        attemptedMediaIds += mediaId

        val currentHeight = failedHeight?.takeIf { it > 0 }
        val alternate = currentHeight?.let { height ->
            candidates.asSequence()
                .mapIndexed { index, candidate -> IndexedCandidate(index, candidate) }
                .filter { indexed ->
                    val candidate = indexed.candidate
                    candidate.mediaId.isNotBlank() &&
                        candidate.mediaId !in attemptedMediaIds &&
                        candidate.height != null &&
                        candidate.height in 1 until height
                }
                .sortedWith(
                    compareByDescending<IndexedCandidate> { it.candidate.height }
                        .thenBy(IndexedCandidate::index),
                )
                .map(IndexedCandidate::candidate)
                .firstOrNull()
        }
        if (alternate != null) {
            attemptedMediaIds += alternate.mediaId
            return DecoderFallbackAction.SwitchStream(
                mediaId = alternate.mediaId,
                height = requireNotNull(alternate.height),
                resumePositionMs = resumePositionMs,
            )
        }

        if (cappedMediaIds.add(mediaId)) {
            return DecoderFallbackAction.RetryCurrentStreamCapped(
                mediaId = mediaId,
                maxHeight = cappedHeight,
                resumePositionMs = resumePositionMs,
            )
        }

        return DecoderFallbackAction.Exhausted(attemptedMediaIds.toSet(), resumePositionMs)
    }

    private data class IndexedCandidate(
        val index: Int,
        val candidate: DecoderStreamCandidate,
    )
}
