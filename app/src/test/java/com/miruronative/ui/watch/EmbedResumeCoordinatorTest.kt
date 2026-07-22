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
    fun `stale mismatch and matching failure retry until matching success latches`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val staleAttempt = checkNotNull(coordinator.nextAttempt(firstVideo))

        coordinator.observe(replacementVideo, positionMs = 0L, isPlaying = false)
        assertEquals(
            EmbedResumeAttemptOutcome.STALE,
            coordinator.acknowledge(staleAttempt, succeeded = true, isPlaying = true, positionMs = 42_000L),
        )

        val failedAttempt = checkNotNull(coordinator.nextAttempt(replacementVideo))
        assertEquals(
            EmbedResumeAttemptOutcome.RETRY,
            coordinator.acknowledge(failedAttempt, succeeded = false, isPlaying = false, positionMs = 0L),
        )
        coordinator.observe(replacementVideo, positionMs = 55_000L, isPlaying = true)
        assertFalse(coordinator.isCompletedForTest(replacementVideo))
        val successfulAttempt = checkNotNull(coordinator.nextAttempt(replacementVideo))
        assertEquals(3, successfulAttempt.number)
        assertEquals(55_000L, successfulAttempt.targetPositionMs)
        assertEquals(
            EmbedResumeAttemptOutcome.COMPLETED,
            coordinator.acknowledge(successfulAttempt, succeeded = true, isPlaying = true, positionMs = 55_000L),
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
    fun `replacement churn consumes one navigation wide attempt budget`() {
        val thirdVideo = EmbedVideoIdentity("episode-c", generation = 3L)
        val fourthVideo = EmbedVideoIdentity("episode-d", generation = 4L)
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L, maxAttempts = 3)

        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        assertEquals(1, checkNotNull(coordinator.nextAttempt(firstVideo)).number)
        coordinator.observe(replacementVideo, positionMs = 0L, isPlaying = false)
        assertEquals(2, checkNotNull(coordinator.nextAttempt(replacementVideo)).number)
        coordinator.observe(thirdVideo, positionMs = 0L, isPlaying = false)
        assertEquals(3, checkNotNull(coordinator.nextAttempt(thirdVideo)).number)
        coordinator.observe(fourthVideo, positionMs = 0L, isPlaying = false)
        assertNull(coordinator.nextAttempt(fourthVideo))

        val freshNavigation = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L, maxAttempts = 3)
        freshNavigation.observe(fourthVideo, positionMs = 0L, isPlaying = false)
        assertEquals(1, checkNotNull(freshNavigation.nextAttempt(fourthVideo)).number)
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
