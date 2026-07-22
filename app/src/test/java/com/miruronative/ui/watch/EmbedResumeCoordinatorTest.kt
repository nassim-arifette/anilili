package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedResumeCoordinatorTest {
    private val firstVideo = EmbedVideoIdentity("episode-a", generation = 1L)
    private val replacementVideo = EmbedVideoIdentity("episode-b", generation = 2L)

    @Test
    fun `pending restore becomes stale and never transfers to replacement video`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val staleAttempt = checkNotNull(coordinator.nextAttempt(firstVideo))

        coordinator.observe(replacementVideo, positionMs = 0L, isPlaying = false)
        assertEquals(
            EmbedResumeAttemptOutcome.STALE,
            coordinator.acknowledge(staleAttempt, succeeded = true, isPlaying = true, positionMs = 42_000L),
        )
        assertTrue(coordinator.isCompletedForTest(replacementVideo))
        assertNull(coordinator.nextAttempt(replacementVideo))
    }

    @Test
    fun `completed video replacement is never automatically resumed or played`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val initialAttempt = checkNotNull(coordinator.nextAttempt(firstVideo))
        coordinator.acknowledge(initialAttempt, succeeded = true, isPlaying = true, positionMs = 42_000L)
        coordinator.observe(firstVideo, positionMs = 58_000L, isPlaying = true)

        coordinator.observe(replacementVideo, positionMs = 0L, isPlaying = false)

        assertTrue(coordinator.isCompletedForTest(replacementVideo))
        assertEquals(0L, coordinator.targetForTest())
        assertNull(coordinator.nextAttempt(replacementVideo))
    }

    @Test
    fun `video already beyond saved target latches without a backwards seek`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)

        coordinator.observe(firstVideo, positionMs = 60_000L, isPlaying = true)

        assertTrue(coordinator.isCompletedForTest(firstVideo))
        assertEquals(60_000L, coordinator.targetForTest())
        assertNull(coordinator.nextAttempt(firstVideo))
    }

    @Test
    fun `paused video at saved target still gets a start attempt`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)

        coordinator.observe(firstVideo, positionMs = 42_000L, isPlaying = false)

        assertFalse(coordinator.isCompletedForTest(firstVideo))
        assertEquals(42_000L, checkNotNull(coordinator.nextAttempt(firstVideo)).targetPositionMs)
    }

    @Test
    fun `paused video beyond target starts from current position without rewinding`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)

        coordinator.observe(firstVideo, positionMs = 60_000L, isPlaying = false)

        assertFalse(coordinator.isCompletedForTest(firstVideo))
        assertEquals(60_000L, checkNotNull(coordinator.nextAttempt(firstVideo)).targetPositionMs)
    }

    @Test
    fun `failed attempt banks a later paused position before retry`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val firstAttempt = checkNotNull(coordinator.nextAttempt(firstVideo))

        assertEquals(
            EmbedResumeAttemptOutcome.RETRY,
            coordinator.acknowledge(
                firstAttempt,
                succeeded = false,
                isPlaying = false,
                positionMs = 60_000L,
            ),
        )

        assertEquals(60_000L, checkNotNull(coordinator.nextAttempt(firstVideo)).targetPositionMs)
    }

    @Test
    fun `playing acknowledgement below target cannot complete restoration`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val firstAttempt = checkNotNull(coordinator.nextAttempt(firstVideo))

        assertEquals(
            EmbedResumeAttemptOutcome.RETRY,
            coordinator.acknowledge(
                firstAttempt,
                succeeded = true,
                isPlaying = true,
                positionMs = 5_000L,
            ),
        )
        assertFalse(coordinator.isCompletedForTest(firstVideo))
        assertEquals(42_000L, checkNotNull(coordinator.nextAttempt(firstVideo)).targetPositionMs)
    }

    @Test
    fun `explicit intent cancels pending resume only for its exact identity`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val pendingAttempt = checkNotNull(coordinator.nextAttempt(firstVideo))

        assertFalse(coordinator.supersedeWithPlaybackIntent(replacementVideo, positionMs = 70_000L))
        assertTrue(coordinator.supersedeWithPlaybackIntent(firstVideo, positionMs = 65_000L))
        assertEquals(
            EmbedResumeAttemptOutcome.STALE,
            coordinator.acknowledge(
                pendingAttempt,
                succeeded = true,
                isPlaying = true,
                positionMs = 42_000L,
            ),
        )
        assertNull(coordinator.nextAttempt(firstVideo))

        coordinator.observe(replacementVideo, positionMs = 0L, isPlaying = false)
        assertNull(coordinator.nextAttempt(replacementVideo))
    }

    @Test
    fun `quality handoff keeps pending restore target instead of transient zero tick`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        checkNotNull(coordinator.nextAttempt(firstVideo))

        assertEquals(42_000L, coordinator.handoffTargetFor(firstVideo))
        assertNull(coordinator.handoffTargetFor(replacementVideo))
        assertNull(coordinator.handoffTargetFor(null))

        assertTrue(coordinator.supersedeWithPlaybackIntent(firstVideo, positionMs = 65_000L))
        assertEquals(65_000L, coordinator.handoffTargetFor(firstVideo))
    }

    @Test
    fun `paused quality handoff seeks replacement and keeps it paused`() {
        val source = EmbedResumeCoordinator(initialTargetPositionMs = 0L)
        source.observe(firstVideo, positionMs = 90_000L, isPlaying = false)
        assertTrue(
            source.supersedeWithPlaybackIntent(
                firstVideo,
                positionMs = 90_000L,
                desiredPlaying = false,
            ),
        )
        val handoff = checkNotNull(source.handoffFor(firstVideo))

        assertEquals(90_000L, handoff.positionMs)
        assertFalse(handoff.desiredPlaying)

        val replacement = EmbedResumeCoordinator(
            initialTargetPositionMs = handoff.positionMs,
            initialDesiredPlaying = handoff.desiredPlaying,
        )
        replacement.observe(replacementVideo, positionMs = 0L, isPlaying = true)
        val attempt = checkNotNull(replacement.nextAttempt(replacementVideo))

        assertFalse(attempt.desiredPlaying)
        assertEquals(90_000L, attempt.targetPositionMs)
        assertEquals(
            EmbedResumeAttemptOutcome.COMPLETED,
            replacement.acknowledge(
                attempt,
                succeeded = true,
                isPlaying = false,
                positionMs = 90_000L,
            ),
        )
        assertNull(replacement.nextAttempt(replacementVideo))
    }

    @Test
    fun `playing quality handoff seeks replacement and resumes it`() {
        val source = EmbedResumeCoordinator(initialTargetPositionMs = 0L)
        source.observe(firstVideo, positionMs = 90_000L, isPlaying = true)
        val handoff = checkNotNull(source.handoffFor(firstVideo))

        assertEquals(90_000L, handoff.positionMs)
        assertTrue(handoff.desiredPlaying)

        val replacement = EmbedResumeCoordinator(
            initialTargetPositionMs = handoff.positionMs,
            initialDesiredPlaying = handoff.desiredPlaying,
        )
        replacement.observe(replacementVideo, positionMs = 0L, isPlaying = false)
        val attempt = checkNotNull(replacement.nextAttempt(replacementVideo))

        assertTrue(attempt.desiredPlaying)
        assertEquals(
            EmbedResumeAttemptOutcome.COMPLETED,
            replacement.acknowledge(
                attempt,
                succeeded = true,
                isPlaying = true,
                positionMs = 90_000L,
            ),
        )
    }

    @Test
    fun `transient paused telemetry never overwrites fresh Play intent`() {
        val coordinator = EmbedResumeCoordinator(
            initialTargetPositionMs = 0L,
            initialDesiredPlaying = true,
        )

        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        assertTrue(checkNotNull(coordinator.handoffFor(firstVideo)).desiredPlaying)

        coordinator.observe(firstVideo, positionMs = 1_000L, isPlaying = true)
        val handoff = checkNotNull(coordinator.handoffFor(firstVideo))
        assertEquals(1_000L, handoff.positionMs)
        assertTrue(handoff.desiredPlaying)
        assertTrue(
            resolveEmbedQualityHandoff(
                confirmedHandoff = handoff,
                currentRequest = EmbedNavigationRequest(
                    streamUrl = "https://player.example/quality-a",
                    documentUrl = "https://player.example/quality-a",
                    allowedMainFrameHost = "player.example",
                    resumePositionMs = 0L,
                    resumeDesiredPlaying = true,
                ),
                allowPlaying = true,
            ).desiredPlaying,
        )
    }

    @Test
    fun `zero-position paused handoff still requires an exact pause barrier`() {
        val source = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        source.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val staleRestore = checkNotNull(source.nextAttempt(firstVideo))
        assertTrue(
            source.supersedeWithPlaybackIntent(
                firstVideo,
                positionMs = 0L,
                desiredPlaying = false,
            ),
        )
        val handoff = checkNotNull(source.handoffFor(firstVideo))

        val replacement = EmbedResumeCoordinator(
            initialTargetPositionMs = handoff.positionMs,
            initialDesiredPlaying = handoff.desiredPlaying,
        )
        replacement.observe(replacementVideo, positionMs = 0L, isPlaying = false)
        assertFalse(replacement.isCompletedForTest(replacementVideo))
        val pauseBarrier = checkNotNull(replacement.nextAttempt(replacementVideo))
        assertEquals(0L, pauseBarrier.targetPositionMs)
        assertFalse(pauseBarrier.desiredPlaying)
        assertEquals(
            EmbedResumeAttemptOutcome.COMPLETED,
            replacement.acknowledge(
                pauseBarrier,
                succeeded = true,
                isPlaying = false,
                positionMs = 0L,
            ),
        )
        assertEquals(
            EmbedResumeAttemptOutcome.STALE,
            source.acknowledge(
                staleRestore,
                succeeded = true,
                isPlaying = true,
                positionMs = 42_000L,
            ),
        )
    }

    @Test
    fun `quality change before replacement tick inherits current request pause intent`() {
        val requestForUntickedReplacement = EmbedNavigationRequest(
            streamUrl = "https://player.example/quality-b",
            documentUrl = "https://player.example/quality-b",
            allowedMainFrameHost = "player.example",
            resumePositionMs = 90_000L,
            resumeDesiredPlaying = false,
        )
        val replacement = EmbedResumeCoordinator(
            initialTargetPositionMs = requestForUntickedReplacement.resumePositionMs,
            initialDesiredPlaying = requestForUntickedReplacement.resumeDesiredPlaying,
        )

        val handoffToQualityC = resolveEmbedQualityHandoff(
            confirmedHandoff = replacement.handoffFor(null),
            currentRequest = requestForUntickedReplacement,
        )

        assertEquals(90_000L, handoffToQualityC.positionMs)
        assertFalse(handoffToQualityC.desiredPlaying)
    }

    @Test
    fun `suppressed quality fallback cannot turn an unticked playing request into Play`() {
        val requestForUntickedReplacement = EmbedNavigationRequest(
            streamUrl = "https://player.example/quality-b",
            documentUrl = "https://player.example/quality-b",
            allowedMainFrameHost = "player.example",
            resumePositionMs = 90_000L,
            resumeDesiredPlaying = true,
        )
        val replacement = EmbedResumeCoordinator(
            initialTargetPositionMs = requestForUntickedReplacement.resumePositionMs,
            initialDesiredPlaying = requestForUntickedReplacement.resumeDesiredPlaying,
        )

        val handoffToQualityC = resolveEmbedQualityHandoff(
            confirmedHandoff = replacement.handoffFor(null),
            currentRequest = requestForUntickedReplacement,
            allowPlaying = false,
        )

        assertEquals(90_000L, handoffToQualityC.positionMs)
        assertFalse(handoffToQualityC.desiredPlaying)
    }

    @Test
    fun `suppression blocks playing restore but permits paused seek and pause`() {
        val paused = EmbedResumeCoordinator(
            initialTargetPositionMs = 42_000L,
            initialDesiredPlaying = false,
        )
        paused.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val pausedAttempt = checkNotNull(paused.nextAttempt(firstVideo, allowPlaying = false))
        assertFalse(pausedAttempt.desiredPlaying)

        val playing = EmbedResumeCoordinator(
            initialTargetPositionMs = 42_000L,
            initialDesiredPlaying = true,
        )
        playing.observe(firstVideo, positionMs = 0L, isPlaying = false)
        assertNull(playing.nextAttempt(firstVideo, allowPlaying = false))
        assertTrue(checkNotNull(playing.nextAttempt(firstVideo, allowPlaying = true)).desiredPlaying)
    }

    @Test
    fun `confirmed pause intent rejects later autoplay from same or replacement video`() {
        val coordinator = EmbedResumeCoordinator(
            initialTargetPositionMs = 0L,
            initialDesiredPlaying = false,
        )
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val initialPause = checkNotNull(coordinator.nextAttempt(firstVideo, allowPlaying = false))
        assertEquals(
            EmbedResumeAttemptOutcome.COMPLETED,
            coordinator.acknowledge(
                initialPause,
                succeeded = true,
                isPlaying = false,
                positionMs = 0L,
            ),
        )

        coordinator.observe(firstVideo, positionMs = 1_000L, isPlaying = true)
        assertFalse(checkNotNull(coordinator.handoffFor(firstVideo)).desiredPlaying)
        assertTrue(coordinator.canSchedulePausedRestore(firstVideo))
        val sameVideoPause = checkNotNull(coordinator.nextAttempt(firstVideo, allowPlaying = false))
        coordinator.acknowledge(
            sameVideoPause,
            succeeded = true,
            isPlaying = false,
            positionMs = 1_000L,
        )

        coordinator.observe(replacementVideo, positionMs = 0L, isPlaying = true)
        assertFalse(checkNotNull(coordinator.handoffFor(replacementVideo)).desiredPlaying)
        assertTrue(coordinator.hasPendingPausedRestore(replacementVideo))
        assertFalse(
            checkNotNull(coordinator.nextAttempt(replacementVideo, allowPlaying = false))
                .desiredPlaying,
        )
    }

    @Test
    fun `lifecycle suppression converts pending Play without losing farther restore target`() {
        val coordinator = EmbedResumeCoordinator(
            initialTargetPositionMs = 90_000L,
            initialDesiredPlaying = true,
        )
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val stalePlayingAttempt = checkNotNull(coordinator.nextAttempt(firstVideo))

        assertTrue(coordinator.suppressAutomaticPlayback(firstVideo, positionMs = 0L))
        assertEquals(
            EmbedResumeAttemptOutcome.STALE,
            coordinator.acknowledge(
                stalePlayingAttempt,
                succeeded = true,
                isPlaying = true,
                positionMs = 90_000L,
            ),
        )
        val pausedRestore = checkNotNull(
            coordinator.nextAttempt(firstVideo, allowPlaying = false),
        )
        assertEquals(90_000L, pausedRestore.targetPositionMs)
        assertFalse(pausedRestore.desiredPlaying)

        // Repeated suppressed telemetry may advance the target but must not cancel this attempt.
        assertTrue(coordinator.suppressAutomaticPlayback(firstVideo, positionMs = 1_000L))
        assertTrue(coordinator.shouldDispatch(pausedRestore))
        assertEquals(90_000L, coordinator.targetForTest())
    }

    @Test
    fun `suppression reuses canceled final Play attempt for seek and pause`() {
        val coordinator = EmbedResumeCoordinator(
            initialTargetPositionMs = 90_000L,
            initialDesiredPlaying = true,
            maxAttempts = 3,
        )
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        assertEquals(EmbedResumeAttemptOutcome.RETRY, coordinator.fail(checkNotNull(coordinator.nextAttempt(firstVideo))))
        assertEquals(EmbedResumeAttemptOutcome.RETRY, coordinator.fail(checkNotNull(coordinator.nextAttempt(firstVideo))))
        val finalPlayAttempt = checkNotNull(coordinator.nextAttempt(firstVideo))
        assertEquals(3, finalPlayAttempt.number)

        assertTrue(coordinator.suppressAutomaticPlayback(firstVideo, positionMs = 0L))
        assertEquals(
            EmbedResumeAttemptOutcome.STALE,
            coordinator.acknowledge(
                finalPlayAttempt,
                succeeded = true,
                isPlaying = true,
                positionMs = 90_000L,
            ),
        )
        val pauseAttempt = checkNotNull(
            coordinator.nextAttempt(firstVideo, allowPlaying = false),
        )
        assertEquals(3, pauseAttempt.number)
        assertEquals(90_000L, pauseAttempt.targetPositionMs)
        assertFalse(pauseAttempt.desiredPlaying)
    }

    @Test
    fun `explicit play after lifecycle pause becomes the next quality intent`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 0L)
        coordinator.observe(firstVideo, positionMs = 12_000L, isPlaying = false)
        assertTrue(
            coordinator.supersedeWithPlaybackIntent(
                firstVideo,
                positionMs = 12_000L,
                desiredPlaying = false,
            ),
        )
        assertTrue(
            coordinator.supersedeWithPlaybackIntent(
                firstVideo,
                positionMs = 12_000L,
                desiredPlaying = true,
            ),
        )
        coordinator.observe(firstVideo, positionMs = 13_000L, isPlaying = true)

        val handoff = checkNotNull(coordinator.handoffFor(firstVideo))
        assertEquals(13_000L, handoff.positionMs)
        assertTrue(handoff.desiredPlaying)
    }

    @Test
    fun `automatic intro seek cannot replace a farther pending saved target`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 600_000L)
        coordinator.observe(firstVideo, positionMs = 10_000L, isPlaying = true)
        val pending = checkNotNull(coordinator.nextAttempt(firstVideo))

        assertEquals(
            EmbedAutomatedSeekDecision.DEFER_TO_PENDING_RESUME,
            coordinator.planAutomatedSeek(firstVideo, positionMs = 90_000L),
        )
        assertEquals(600_000L, coordinator.targetForTest())
        assertTrue(coordinator.shouldDispatch(pending))
    }

    @Test
    fun `automatic intro seek only advances a nearer pending restore target`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        coordinator.observe(firstVideo, positionMs = 10_000L, isPlaying = true)
        val pending = checkNotNull(coordinator.nextAttempt(firstVideo))

        assertEquals(
            EmbedAutomatedSeekDecision.DEFER_TO_PENDING_RESUME,
            coordinator.planAutomatedSeek(firstVideo, positionMs = 90_000L),
        )
        assertEquals(90_000L, coordinator.targetForTest())
        assertEquals(
            EmbedResumeAttemptOutcome.RETRY,
            coordinator.acknowledge(
                pending,
                succeeded = true,
                isPlaying = true,
                positionMs = 42_000L,
            ),
        )
        assertEquals(90_000L, checkNotNull(coordinator.nextAttempt(firstVideo)).targetPositionMs)
    }

    @Test
    fun `automatic seek dispatches only after restoration completed for exact video`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val pending = checkNotNull(coordinator.nextAttempt(firstVideo))
        coordinator.acknowledge(pending, succeeded = true, isPlaying = true, positionMs = 42_000L)

        assertEquals(
            EmbedAutomatedSeekDecision.DISPATCH,
            coordinator.planAutomatedSeek(firstVideo, positionMs = 90_000L),
        )
        assertEquals(
            EmbedAutomatedSeekDecision.REJECT,
            coordinator.planAutomatedSeek(replacementVideo, positionMs = 90_000L),
        )
    }

    @Test
    fun `automatic seek can safely take over after restore attempts exhaust`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 600_000L, maxAttempts = 1)
        coordinator.observe(firstVideo, positionMs = 10_000L, isPlaying = true)
        val onlyAttempt = checkNotNull(coordinator.nextAttempt(firstVideo))
        assertEquals(EmbedResumeAttemptOutcome.EXHAUSTED, coordinator.fail(onlyAttempt))

        assertEquals(
            EmbedAutomatedSeekDecision.REJECT,
            coordinator.planAutomatedSeek(firstVideo, positionMs = 5_000L),
        )
        assertEquals(
            EmbedAutomatedSeekDecision.DISPATCH,
            coordinator.planAutomatedSeek(firstVideo, positionMs = 90_000L),
        )
    }

    @Test
    fun `lifecycle pause cancels in flight and queued retries without resume resurrection`() {
        val initialCoordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        initialCoordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        assertTrue(
            initialCoordinator.supersedeWithPlaybackIntent(
                firstVideo,
                positionMs = 0L,
                desiredPlaying = false,
            ),
        )
        initialCoordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        assertNull(initialCoordinator.nextAttempt(firstVideo))

        val inFlightCoordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        inFlightCoordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val inFlight = checkNotNull(inFlightCoordinator.nextAttempt(firstVideo))
        assertTrue(
            inFlightCoordinator.supersedeWithPlaybackIntent(
                firstVideo,
                positionMs = 0L,
                desiredPlaying = false,
            ),
        )
        assertEquals(
            EmbedResumeAttemptOutcome.STALE,
            inFlightCoordinator.acknowledge(
                inFlight,
                succeeded = false,
                isPlaying = false,
                positionMs = 0L,
            ),
        )
        inFlightCoordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        assertNull(inFlightCoordinator.nextAttempt(firstVideo))

        val retryCoordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        retryCoordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val failed = checkNotNull(retryCoordinator.nextAttempt(firstVideo))
        assertEquals(EmbedResumeAttemptOutcome.RETRY, retryCoordinator.fail(failed))
        assertTrue(
            retryCoordinator.supersedeWithPlaybackIntent(
                firstVideo,
                positionMs = 0L,
                desiredPlaying = false,
            ),
        )
        retryCoordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        assertNull(retryCoordinator.nextAttempt(firstVideo))
    }

    @Test
    fun `failures stop after bounded attempt count`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L, maxAttempts = 2)
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val first = checkNotNull(coordinator.nextAttempt(firstVideo))
        assertEquals(EmbedResumeAttemptOutcome.RETRY, coordinator.fail(first))
        val second = checkNotNull(coordinator.nextAttempt(firstVideo))
        assertEquals(EmbedResumeAttemptOutcome.EXHAUSTED, coordinator.fail(second))
        assertNull(coordinator.nextAttempt(firstVideo))
    }
}
