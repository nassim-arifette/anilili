package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedProgressPolicyTest {
    @Test
    fun `missing or non finite progress is rejected`() {
        assertNull(normalizeEmbedProgress(positionMs = -1L, durationMs = 60_000L))
        assertNull(normalizeEmbedProgress(positionMs = 10_000L, durationMs = 0L))
        assertNull(embedProgressFromSeconds(0.0, 60.0))
        assertNull(embedProgressFromSeconds(Double.NaN, 60.0))
        assertNull(embedProgressFromSeconds(10.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun `known duration makes zero valid and clamps provider overshoot`() {
        assertEquals(
            EmbedProgressSample(positionMs = 0L, durationMs = 60_000L),
            normalizeEmbedProgress(positionMs = 0L, durationMs = 60_000L),
        )
        assertEquals(
            EmbedProgressSample(positionMs = 60_000L, durationMs = 60_000L),
            normalizeEmbedProgress(positionMs = 61_000L, durationMs = 60_000L),
        )
    }

    @Test
    fun `paused seek becomes the next finalization and duplicate flushes are suppressed`() {
        val policy = EmbedProgressFinalizationPolicy()

        policy.observe(positionMs = 15_000L, durationMs = 60_000L)
        assertEquals(
            EmbedProgressSample(positionMs = 15_000L, durationMs = 60_000L),
            policy.takePendingFinalization(),
        )
        assertNull(policy.takePendingFinalization())

        policy.observe(positionMs = 42_000L, durationMs = 60_000L)
        assertEquals(
            EmbedProgressSample(positionMs = 42_000L, durationMs = 60_000L),
            policy.takePendingFinalization(),
        )
        assertNull(policy.takePendingFinalization())
    }

    @Test
    fun `invalid late sample does not erase the last known position`() {
        val policy = EmbedProgressFinalizationPolicy()

        policy.observe(positionMs = 27_000L, durationMs = 60_000L)
        assertNull(policy.observe(positionMs = 0L, durationMs = 0L))

        assertEquals(
            EmbedProgressSample(positionMs = 27_000L, durationMs = 60_000L),
            policy.takePendingFinalization(),
        )
    }

    @Test
    fun `release gate closes once and stays closed`() {
        val gate = WebProgressCallbackGate()

        assertTrue(gate.isOpen)
        assertTrue(gate.close())
        assertFalse(gate.isOpen)
        assertFalse(gate.close())
    }
}
