package com.miruronative.ui.watch

internal const val EMBED_RESUME_MAX_ATTEMPTS = 3
internal const val EMBED_RESUME_RETRY_DELAY_MS = 750L
private const val EMBED_RESUME_POSITION_TOLERANCE_MS = 1_500L

internal data class EmbedResumeAttempt(
    val mediaIdentity: EmbedVideoIdentity,
    val targetPositionMs: Long,
    val desiredPlaying: Boolean,
    val number: Int,
)

internal data class EmbedResumeHandoff(
    val positionMs: Long,
    val desiredPlaying: Boolean,
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
    initialDesiredPlaying: Boolean = true,
    private val maxAttempts: Int = EMBED_RESUME_MAX_ATTEMPTS,
    private val positionToleranceMs: Long = EMBED_RESUME_POSITION_TOLERANCE_MS,
) {
    private var activeMediaIdentity: EmbedVideoIdentity? = null
    private var targetPositionMs = initialTargetPositionMs.coerceAtLeast(0L)
    private var desiredPlaying = initialDesiredPlaying
    private var lastObservedPositionMs = 0L
    private var totalAttempts = 0
    private var activeIdentityCompleted = targetPositionMs == 0L && desiredPlaying
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
                // A durable pause intent also owns unexpected same-document replacements. Adopt
                // neither their autoplay nor their identity as permission to start playback.
                if (desiredPlaying) {
                    desiredPlaying = isPlaying
                    activeIdentityCompleted = true
                } else {
                    activeIdentityCompleted = !isPlaying
                }
                restorationExhausted = false
            } else if (targetPositionMs <= 0L && desiredPlaying) {
                targetPositionMs = safePositionMs
                desiredPlaying = isPlaying
                activeIdentityCompleted = true
                restorationExhausted = false
            } else {
                val positionSatisfied = hasReachedTarget(safePositionMs)
                if (positionSatisfied) bankForwardPosition(safePositionMs)
                // A paused restore must execute its pause-all barrier at least once. A selected
                // video already being paused is not proof that a hidden video/audio sibling is.
                activeIdentityCompleted =
                    positionSatisfied && desiredPlaying && playbackStateMatches(isPlaying)
            }
            return
        }

        lastObservedPositionMs = safePositionMs
        if (activeIdentityCompleted) {
            // Keep the latest confirmed position for diagnostics and explicit playback intent.
            targetPositionMs = safePositionMs
            if (!desiredPlaying && isPlaying) {
                // Provider autoplay after a confirmed pause is not a new user intent. Re-open the
                // pause barrier; only supersedeWithPlaybackIntent(..., true) may release it.
                activeIdentityCompleted = false
                restorationExhausted = false
                inFlightAttempt = null
            } else if (desiredPlaying) {
                desiredPlaying = isPlaying
            }
        } else if (hasReachedTarget(safePositionMs)) {
            // Never pull a paused video backwards just to prove that it can play. Bank its current
            // position for the pending start attempt, while still requiring a matching playing
            // acknowledgement once an automatic command has already been issued.
            bankForwardPosition(safePositionMs)
            if (totalAttempts == 0 && desiredPlaying && playbackStateMatches(isPlaying)) {
                activeIdentityCompleted = true
                inFlightAttempt = null
            }
        }
    }

    /** An app/user playback mutation supersedes automatic restoration for this exact video. */
    @Synchronized
    fun supersedeWithPlaybackIntent(
        mediaIdentity: EmbedVideoIdentity,
        positionMs: Long,
        desiredPlaying: Boolean = true,
    ): Boolean {
        if (!mediaIdentity.isValid || mediaIdentity != activeMediaIdentity) return false
        val safePositionMs = positionMs.coerceAtLeast(0L)
        activeIdentityCompleted = true
        restorationExhausted = false
        inFlightAttempt = null
        lastObservedPositionMs = safePositionMs
        targetPositionMs = safePositionMs
        this.desiredPlaying = desiredPlaying
        return true
    }

    /**
     * Lifecycle/provider suppression is not a seek. It converts a pending Play into seek+pause
     * without discarding the farther saved target, and repeated suppressed ticks leave an existing
     * pause attempt intact.
     */
    @Synchronized
    fun suppressAutomaticPlayback(
        mediaIdentity: EmbedVideoIdentity,
        positionMs: Long,
    ): Boolean {
        if (!mediaIdentity.isValid || mediaIdentity != activeMediaIdentity) return false
        val safePositionMs = positionMs.coerceAtLeast(0L)
        lastObservedPositionMs = safePositionMs
        bankForwardPosition(safePositionMs)
        if (desiredPlaying) {
            desiredPlaying = false
            activeIdentityCompleted = false
            restorationExhausted = false
            inFlightAttempt = null
        }
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

    /** Carries exact position and pause/play intent into an explicit fresh quality navigation. */
    @Synchronized
    fun handoffFor(mediaIdentity: EmbedVideoIdentity?): EmbedResumeHandoff? =
        if (mediaIdentity?.isValid == true && mediaIdentity == activeMediaIdentity) {
            EmbedResumeHandoff(targetPositionMs, desiredPlaying)
        } else {
            null
        }

    @Synchronized
    fun hasPendingPausedRestore(mediaIdentity: EmbedVideoIdentity): Boolean =
        mediaIdentity == activeMediaIdentity &&
            !desiredPlaying &&
            !activeIdentityCompleted &&
            !restorationExhausted

    @Synchronized
    fun canSchedulePausedRestore(mediaIdentity: EmbedVideoIdentity): Boolean =
        mediaIdentity == activeMediaIdentity &&
            !desiredPlaying &&
            !activeIdentityCompleted &&
            !restorationExhausted &&
            inFlightAttempt == null &&
            totalAttempts < maxAttempts

    @Synchronized
    fun nextAttempt(
        mediaIdentity: EmbedVideoIdentity,
        allowPlaying: Boolean = true,
    ): EmbedResumeAttempt? {
        if (
            mediaIdentity != activeMediaIdentity ||
            activeIdentityCompleted ||
            restorationExhausted ||
            (desiredPlaying && !allowPlaying) ||
            (targetPositionMs <= 0L && desiredPlaying) ||
            inFlightAttempt != null ||
            totalAttempts >= maxAttempts
        ) {
            return null
        }
        totalAttempts += 1
        return EmbedResumeAttempt(
            mediaIdentity = mediaIdentity,
            targetPositionMs = targetPositionMs,
            desiredPlaying = desiredPlaying,
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
        if (succeeded && isPlaying == attempt.desiredPlaying && hasReachedTarget(safePositionMs)) {
            activeIdentityCompleted = true
            restorationExhausted = false
            targetPositionMs = safePositionMs
            desiredPlaying = isPlaying
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

    private fun playbackStateMatches(isPlaying: Boolean): Boolean = isPlaying == desiredPlaying

    private fun bankForwardPosition(positionMs: Long) {
        targetPositionMs = maxOf(targetPositionMs, positionMs)
    }
}

/**
 * A quality click may race ahead of the replacement document's first concrete-video sample. In
 * that window the current navigation request is the only authenticated position/playback intent;
 * never fall back to mutable state left over from the preceding navigation.
 */
internal fun resolveEmbedQualityHandoff(
    confirmedHandoff: EmbedResumeHandoff?,
    currentRequest: EmbedNavigationRequest,
    allowPlaying: Boolean = true,
): EmbedResumeHandoff {
    val resolved = confirmedHandoff ?: EmbedResumeHandoff(
        positionMs = currentRequest.resumePositionMs,
        desiredPlaying = currentRequest.resumeDesiredPlaying,
    )
    return resolved.copy(desiredPlaying = resolved.desiredPlaying && allowPlaying)
}
