package com.miruronative.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPlaybackOwnerPolicyTest {
    @Test
    fun `incoming watch screen invalidates every outgoing player operation`() {
        val owners = WatchPlaybackOwnerRegistry()
        val outgoing = owners.activate()
        val incoming = owners.activate()
        var outgoingOperations = 0
        var incomingOperations = 0

        assertFalse(owners.runIfActive(outgoing) { outgoingOperations++ })
        assertTrue(owners.runIfActive(incoming) { incomingOperations++ })
        assertEquals(0, outgoingOperations)
        assertEquals(1, incomingOperations)
    }

    @Test
    fun `late outgoing teardown cannot release incoming screen`() {
        val owners = WatchPlaybackOwnerRegistry()
        val outgoing = owners.activate()
        val incoming = owners.activate()

        assertFalse(owners.release(outgoing))
        assertTrue(owners.isActive(incoming))
        assertTrue(owners.runIfActive(incoming) {})
    }

    @Test
    fun `incoming embed or terminal owner may stop stale native media`() {
        val owners = WatchPlaybackOwnerRegistry()
        val outgoingNative = owners.activate()
        val incomingTerminal = owners.activate()
        var mediaLoaded = true

        assertTrue(owners.runIfActive(incomingTerminal) { mediaLoaded = false })
        assertFalse(owners.runIfActive(outgoingNative) { mediaLoaded = true })
        assertFalse(mediaLoaded)
    }

    @Test
    fun `current screen release leaves no owner until another screen activates`() {
        val owners = WatchPlaybackOwnerRegistry()
        val owner = owners.activate()

        assertTrue(owners.release(owner))
        assertFalse(owners.isActive(owner))
        assertFalse(owners.runIfActive(owner) {})
    }

    @Test
    fun `tokens from a different registry never alias by generation`() {
        val firstRegistry = WatchPlaybackOwnerRegistry()
        val secondRegistry = WatchPlaybackOwnerRegistry()
        val first = firstRegistry.activate()
        val foreign = secondRegistry.activate()

        assertEquals(first.generation, foreign.generation)
        assertFalse(firstRegistry.isActive(foreign))
        assertTrue(firstRegistry.isActive(first))
    }
}
