package com.miruronative.playback

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
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
    fun `incoming owner waits for outgoing stop and flush before activation`() {
        val owners = WatchPlaybackOwnerRegistry()
        val outgoing = owners.activate()
        val events = mutableListOf<String>()

        assertTrue(
            owners.registerOutgoingHandoff(outgoing) {
                assertTrue(owners.isActive(outgoing))
                events += "stop-embed"
                events += "flush-progress"
            },
        )

        val incoming = owners.activate()
        assertTrue(owners.runIfActive(incoming) { events += "stop-stale-native" })

        assertEquals(listOf("stop-embed", "flush-progress", "stop-stale-native"), events)
        assertFalse(owners.isActive(outgoing))
        assertTrue(owners.isActive(incoming))
    }

    @Test
    fun `concurrent activation cannot return while outgoing handoff is running`() {
        val owners = WatchPlaybackOwnerRegistry()
        val outgoing = owners.activate()
        val handoffStarted = CountDownLatch(1)
        val finishHandoff = CountDownLatch(1)
        val activationFinished = CountDownLatch(1)
        val incoming = AtomicReference<WatchPlaybackOwnerToken>()
        assertTrue(
            owners.registerOutgoingHandoff(outgoing) {
                handoffStarted.countDown()
                assertTrue(finishHandoff.await(2, TimeUnit.SECONDS))
            },
        )

        val activationThread = thread(name = "incoming-watch-owner") {
            incoming.set(owners.activate())
            activationFinished.countDown()
        }

        assertTrue(handoffStarted.await(2, TimeUnit.SECONDS))
        assertFalse(activationFinished.await(100, TimeUnit.MILLISECONDS))
        finishHandoff.countDown()
        assertTrue(activationFinished.await(2, TimeUnit.SECONDS))
        activationThread.join(2_000)

        assertFalse(activationThread.isAlive)
        assertFalse(owners.isActive(outgoing))
        assertTrue(owners.isActive(incoming.get()))
    }

    @Test
    fun `outgoing handoff is consumed exactly once`() {
        val owners = WatchPlaybackOwnerRegistry()
        val outgoing = owners.activate()
        var calls = 0
        assertTrue(owners.registerOutgoingHandoff(outgoing) { calls++ })

        owners.activate()
        owners.activate()

        assertEquals(1, calls)
    }

    @Test
    fun `stale clear and release cannot remove incoming handoff`() {
        val owners = WatchPlaybackOwnerRegistry()
        val outgoing = owners.activate()
        val incoming = owners.activate()
        var incomingHandoffs = 0
        assertTrue(owners.registerOutgoingHandoff(incoming) { incomingHandoffs++ })

        assertFalse(owners.registerOutgoingHandoff(outgoing) { incomingHandoffs += 100 })
        assertFalse(owners.clearOutgoingHandoff(outgoing))
        assertFalse(owners.release(outgoing))
        owners.activate()

        assertEquals(1, incomingHandoffs)
    }

    @Test
    fun `handoff failure is reported without blocking incoming activation`() {
        val owners = WatchPlaybackOwnerRegistry()
        val outgoing = owners.activate()
        val failure = IllegalStateException("broken teardown")
        var reported: Throwable? = null
        assertTrue(owners.registerOutgoingHandoff(outgoing) { throw failure })

        val incoming = owners.activate { reported = it }

        assertEquals(failure, reported)
        assertFalse(owners.isActive(outgoing))
        assertTrue(owners.isActive(incoming))
    }

    @Test
    fun `current release clears its unconsumed handoff`() {
        val owners = WatchPlaybackOwnerRegistry()
        val outgoing = owners.activate()
        var calls = 0
        assertTrue(owners.registerOutgoingHandoff(outgoing) { calls++ })

        assertTrue(owners.release(outgoing))
        owners.activate()

        assertEquals(0, calls)
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
