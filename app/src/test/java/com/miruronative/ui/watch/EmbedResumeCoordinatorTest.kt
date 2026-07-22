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
        assertTrue(initialCoordinator.supersedeWithPlaybackIntent(firstVideo, positionMs = 0L))
        initialCoordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        assertNull(initialCoordinator.nextAttempt(firstVideo))

        val inFlightCoordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        inFlightCoordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val inFlight = checkNotNull(inFlightCoordinator.nextAttempt(firstVideo))
        assertTrue(inFlightCoordinator.supersedeWithPlaybackIntent(firstVideo, positionMs = 0L))
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
        assertTrue(retryCoordinator.supersedeWithPlaybackIntent(firstVideo, positionMs = 0L))
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
