package com.miruronative.ui.watch

internal const val EMBED_RESUME_MAX_ATTEMPTS = 3
internal const val EMBED_RESUME_RETRY_DELAY_MS = 750L
private const val EMBED_RESUME_POSITION_TOLERANCE_MS = 1_500L

internal data class EmbedResumeAttempt(
    val mediaIdentity: EmbedVideoIdentity,
    val targetPositionMs: Long,
    val number: Int,
)

internal enum class EmbedResumeAttemptOutcome {
    STALE,
    RETRY,
    COMPLETED,
    EXHAUSTED,
}

/**
 * Coordinates saved-position restoration across concrete videos in one embed navigation.
 *
 * Completion is identity-bound. If the document replaces its selected video, the replacement gets
 * a fresh bounded attempt using the last position confirmed for the former video. An unresolved
 * former video keeps the original target, while a video already at or beyond that target is never
 * pulled backwards.
 */
internal class EmbedResumeCoordinator(
    initialTargetPositionMs: Long,
    private val maxAttempts: Int = EMBED_RESUME_MAX_ATTEMPTS,
    private val positionToleranceMs: Long = EMBED_RESUME_POSITION_TOLERANCE_MS,
) {
    private var activeMediaIdentity: EmbedVideoIdentity? = null
    private var targetPositionMs = initialTargetPositionMs.coerceAtLeast(0L)
    private var lastObservedPositionMs = 0L
    private var attemptsForActiveIdentity = 0
    private var activeIdentityCompleted = targetPositionMs == 0L
    private var inFlightAttempt: EmbedResumeAttempt? = null

    init {
        require(maxAttempts > 0)
        require(positionToleranceMs >= 0L)
    }

    @Synchronized
    fun observe(mediaIdentity: EmbedVideoIdentity, positionMs: Long, isPlaying: Boolean) {
        if (!mediaIdentity.isValid) return
        val safePositionMs = positionMs.coerceAtLeast(0L)
        if (mediaIdentity != activeMediaIdentity) {
            if (activeMediaIdentity != null && activeIdentityCompleted) {
                targetPositionMs = lastObservedPositionMs
            }
            activeMediaIdentity = mediaIdentity
            lastObservedPositionMs = safePositionMs
            attemptsForActiveIdentity = 0
            inFlightAttempt = null
            activeIdentityCompleted = isTargetAlreadySatisfied(safePositionMs, isPlaying)
            if (activeIdentityCompleted) targetPositionMs = safePositionMs
            return
        }

        lastObservedPositionMs = safePositionMs
        if (activeIdentityCompleted) {
            // Track deliberate seeks as well as ordinary progress so a later quality/video handoff
            // resumes from what the viewer most recently confirmed, not the original saved value.
            targetPositionMs = safePositionMs
        } else if (
            attemptsForActiveIdentity == 0 &&
            isTargetAlreadySatisfied(safePositionMs, isPlaying)
        ) {
            activeIdentityCompleted = true
            inFlightAttempt = null
            targetPositionMs = safePositionMs
        } else if (isPlaying && hasReachedTarget(safePositionMs)) {
            // An exact tick can prove the command's intended position is no longer current without
            // proving its acknowledgement. Bank that newer point for a retry/replacement, but keep
            // the latch open until the matching command acknowledgement arrives.
            targetPositionMs = safePositionMs
        }
    }

    @Synchronized
    fun nextAttempt(mediaIdentity: EmbedVideoIdentity): EmbedResumeAttempt? {
        if (
            mediaIdentity != activeMediaIdentity ||
            activeIdentityCompleted ||
            targetPositionMs <= 0L ||
            inFlightAttempt != null ||
            attemptsForActiveIdentity >= maxAttempts
        ) {
            return null
        }
        attemptsForActiveIdentity += 1
        return EmbedResumeAttempt(
            mediaIdentity = mediaIdentity,
            targetPositionMs = targetPositionMs,
            number = attemptsForActiveIdentity,
        ).also { inFlightAttempt = it }
    }

    @Synchronized
    fun shouldDispatch(attempt: EmbedResumeAttempt): Boolean =
        inFlightAttempt == attempt &&
            activeMediaIdentity == attempt.mediaIdentity &&
            !activeIdentityCompleted

    @Synchronized
    fun acknowledge(
        attempt: EmbedResumeAttempt,
        succeeded: Boolean,
        isPlaying: Boolean,
        positionMs: Long,
    ): EmbedResumeAttemptOutcome {
        if (!shouldResolve(attempt)) return EmbedResumeAttemptOutcome.STALE
        inFlightAttempt = null
        if (succeeded && isPlaying) {
            activeIdentityCompleted = true
            lastObservedPositionMs = positionMs.coerceAtLeast(0L)
            targetPositionMs = lastObservedPositionMs
            return EmbedResumeAttemptOutcome.COMPLETED
        }
        return retryOrExhausted()
    }

    @Synchronized
    fun fail(attempt: EmbedResumeAttempt): EmbedResumeAttemptOutcome {
        if (!shouldResolve(attempt)) return EmbedResumeAttemptOutcome.STALE
        inFlightAttempt = null
        return retryOrExhausted()
    }

    @Synchronized
    internal fun targetForTest(): Long = targetPositionMs

    @Synchronized
    internal fun isCompletedForTest(mediaIdentity: EmbedVideoIdentity): Boolean =
        activeMediaIdentity == mediaIdentity && activeIdentityCompleted

    private fun shouldResolve(attempt: EmbedResumeAttempt): Boolean =
        inFlightAttempt == attempt && activeMediaIdentity == attempt.mediaIdentity

    private fun retryOrExhausted(): EmbedResumeAttemptOutcome =
        if (attemptsForActiveIdentity < maxAttempts) {
            EmbedResumeAttemptOutcome.RETRY
        } else {
            EmbedResumeAttemptOutcome.EXHAUSTED
        }

    private fun isTargetAlreadySatisfied(positionMs: Long, isPlaying: Boolean): Boolean =
        targetPositionMs <= 0L ||
            (isPlaying && hasReachedTarget(positionMs))

    private fun hasReachedTarget(positionMs: Long): Boolean =
        positionMs + positionToleranceMs >= targetPositionMs
}
