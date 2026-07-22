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

internal enum class EmbedAutomatedSeekDecision {
    DISPATCH,
    DEFER_TO_PENDING_RESUME,
    REJECT,
}

/**
 * Coordinates saved-position restoration across concrete videos in one embed navigation.
 *
 * Completion is identity-bound. Every same-document concrete-video replacement is deliberately
 * left untouched, even when the initial restore is unresolved: it may be a provider-controlled next
 * episode or a SUB/DUB alternate. Explicit app quality changes create a new navigation/coordinator
 * with an identity-safe handoff target. The attempt budget is navigation-wide, and a video already
 * beyond the pending target is never pulled backwards.
 */
internal class EmbedResumeCoordinator(
    initialTargetPositionMs: Long,
    private val maxAttempts: Int = EMBED_RESUME_MAX_ATTEMPTS,
    private val positionToleranceMs: Long = EMBED_RESUME_POSITION_TOLERANCE_MS,
) {
    private var activeMediaIdentity: EmbedVideoIdentity? = null
    private var targetPositionMs = initialTargetPositionMs.coerceAtLeast(0L)
    private var lastObservedPositionMs = 0L
    private var totalAttempts = 0
    private var activeIdentityCompleted = targetPositionMs == 0L
    private var restorationExhausted = false
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
            val replacesConcreteVideo = activeMediaIdentity != null
            activeMediaIdentity = mediaIdentity
            lastObservedPositionMs = safePositionMs
            inFlightAttempt = null
            if (replacesConcreteVideo) {
                // Never infer that an arbitrary replacement is the same episode. Quality changes
                // initiated by the app create a fresh navigation/coordinator with their own target.
                targetPositionMs = safePositionMs
                activeIdentityCompleted = true
                restorationExhausted = false
            } else if (targetPositionMs <= 0L) {
                targetPositionMs = safePositionMs
                activeIdentityCompleted = true
                restorationExhausted = false
            } else {
                val positionSatisfied = hasReachedTarget(safePositionMs)
                if (positionSatisfied) bankForwardPosition(safePositionMs)
                activeIdentityCompleted = positionSatisfied && isPlaying
            }
            return
        }

        lastObservedPositionMs = safePositionMs
        if (activeIdentityCompleted) {
            // Keep the latest confirmed position for diagnostics and explicit playback intent.
            targetPositionMs = safePositionMs
        } else if (hasReachedTarget(safePositionMs)) {
            // Never pull a paused video backwards just to prove that it can play. Bank its current
            // position for the pending start attempt, while still requiring a matching playing
            // acknowledgement once an automatic command has already been issued.
            bankForwardPosition(safePositionMs)
            if (totalAttempts == 0 && isPlaying) {
                activeIdentityCompleted = true
                inFlightAttempt = null
            }
        }
    }

    /** An app/user playback mutation supersedes automatic restoration for this exact video. */
    @Synchronized
    fun supersedeWithPlaybackIntent(mediaIdentity: EmbedVideoIdentity, positionMs: Long): Boolean {
        if (!mediaIdentity.isValid || mediaIdentity != activeMediaIdentity) return false
        val safePositionMs = positionMs.coerceAtLeast(0L)
        activeIdentityCompleted = true
        restorationExhausted = false
        inFlightAttempt = null
        lastObservedPositionMs = safePositionMs
        targetPositionMs = safePositionMs
        return true
    }

    /**
     * Keeps automatic skips ordered behind restoration. While restore is pending, AniSkip may move
     * its target forward but never dispatch a competing/backwards seek or cancel saved progress.
     */
    @Synchronized
    fun planAutomatedSeek(
        mediaIdentity: EmbedVideoIdentity,
        positionMs: Long,
    ): EmbedAutomatedSeekDecision {
        if (!mediaIdentity.isValid || mediaIdentity != activeMediaIdentity) {
            return EmbedAutomatedSeekDecision.REJECT
        }
        if (!activeIdentityCompleted && !restorationExhausted) {
            bankForwardPosition(positionMs.coerceAtLeast(0L))
            return EmbedAutomatedSeekDecision.DEFER_TO_PENDING_RESUME
        }
        if (positionMs.coerceAtLeast(0L) < lastObservedPositionMs) {
            return EmbedAutomatedSeekDecision.REJECT
        }
        return EmbedAutomatedSeekDecision.DISPATCH
    }

    /** Returns a quality-navigation handoff target only for the currently selected exact video. */
    @Synchronized
    fun handoffTargetFor(mediaIdentity: EmbedVideoIdentity?): Long? =
        targetPositionMs.takeIf {
            mediaIdentity?.isValid == true && mediaIdentity == activeMediaIdentity
        }

    @Synchronized
    fun nextAttempt(mediaIdentity: EmbedVideoIdentity): EmbedResumeAttempt? {
        if (
            mediaIdentity != activeMediaIdentity ||
            activeIdentityCompleted ||
            restorationExhausted ||
            targetPositionMs <= 0L ||
            inFlightAttempt != null ||
            totalAttempts >= maxAttempts
        ) {
            return null
        }
        totalAttempts += 1
        return EmbedResumeAttempt(
            mediaIdentity = mediaIdentity,
            targetPositionMs = targetPositionMs,
            number = totalAttempts,
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
        val safePositionMs = positionMs.coerceAtLeast(0L)
        lastObservedPositionMs = safePositionMs
        if (hasReachedTarget(safePositionMs)) bankForwardPosition(safePositionMs)
        if (succeeded && isPlaying && hasReachedTarget(safePositionMs)) {
            activeIdentityCompleted = true
            restorationExhausted = false
            targetPositionMs = safePositionMs
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

    private fun retryOrExhausted(): EmbedResumeAttemptOutcome = if (totalAttempts < maxAttempts) {
        EmbedResumeAttemptOutcome.RETRY
    } else {
        restorationExhausted = true
        EmbedResumeAttemptOutcome.EXHAUSTED
    }

    private fun hasReachedTarget(positionMs: Long): Boolean =
        positionMs >= (targetPositionMs - positionToleranceMs).coerceAtLeast(0L)

    private fun bankForwardPosition(positionMs: Long) {
        targetPositionMs = maxOf(targetPositionMs, positionMs)
    }
}
