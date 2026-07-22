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
        assertEquals(2, successfulAttempt.number)
        assertEquals(55_000L, successfulAttempt.targetPositionMs)
        assertEquals(
            EmbedResumeAttemptOutcome.COMPLETED,
            coordinator.acknowledge(successfulAttempt, succeeded = true, isPlaying = true, positionMs = 55_000L),
        )
        assertTrue(coordinator.isCompletedForTest(replacementVideo))
        assertNull(coordinator.nextAttempt(replacementVideo))
    }

    @Test
    fun `replacement resumes from latest confirmed position instead of obsolete saved target`() {
        val coordinator = EmbedResumeCoordinator(initialTargetPositionMs = 42_000L)
        coordinator.observe(firstVideo, positionMs = 0L, isPlaying = false)
        val initialAttempt = checkNotNull(coordinator.nextAttempt(firstVideo))
        coordinator.acknowledge(initialAttempt, succeeded = true, isPlaying = true, positionMs = 42_000L)
        coordinator.observe(firstVideo, positionMs = 58_000L, isPlaying = true)

        coordinator.observe(replacementVideo, positionMs = 0L, isPlaying = false)
        val replacementAttempt = checkNotNull(coordinator.nextAttempt(replacementVideo))
        assertEquals(58_000L, replacementAttempt.targetPositionMs)
        assertFalse(coordinator.isCompletedForTest(replacementVideo))
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
