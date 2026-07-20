package com.miruronative.ui.watch

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestGateTest {

    @Test
    fun `new playback request invalidates old resolution but keeps catalog session current`() {
        val gate = PlaybackRequestGate()
        val first = gate.startSession()
        val second = gate.nextRequest()

        assertTrue(gate.isCurrentSession(first))
        assertFalse(gate.isCurrentRequest(first))
        assertTrue(gate.isCurrentRequest(second))
    }

    @Test
    fun `new watch session invalidates old merges and resolutions`() {
        val gate = PlaybackRequestGate()
        val old = gate.startSession()
        val current = gate.startSession()

        assertFalse(gate.isCurrentSession(old))
        assertFalse(gate.isCurrentRequest(old))
        assertTrue(gate.isCurrentSession(current))
        assertTrue(gate.isCurrentRequest(current))
    }

    @Test
    fun `stale session guard cancels a late catalog merge`() {
        val gate = PlaybackRequestGate()
        val stale = gate.startSession()
        gate.startSession()
        var merged = false

        try {
            gate.requireCurrentSession(stale)
            merged = true
        } catch (_: CancellationException) {
            // Expected: results from the previous title never reach shared catalog state.
        }

        assertFalse(merged)
    }

    @Test
    fun `stale request guard cancels before caller can publish`() {
        val gate = PlaybackRequestGate()
        val stale = gate.startSession()
        gate.nextRequest()
        var published = false

        try {
            gate.requireCurrentRequest(stale)
            published = true
        } catch (_: CancellationException) {
            // Expected: stale asynchronous work exits through the normal cancellation path.
        }

        assertFalse(published)
    }

    @Test
    fun `finishing stale work cannot mark the current request complete`() {
        val gate = PlaybackRequestGate()
        val stale = gate.startSession()
        val current = gate.nextRequest()

        assertFalse(gate.finishRequest(stale))
        assertTrue(gate.hasPendingRequest())
        assertTrue(gate.finishRequest(current))
        assertFalse(gate.hasPendingRequest())
    }
}
