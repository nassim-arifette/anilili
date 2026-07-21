package com.miruronative.ui.watch

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionBarrierTest {
    @Test
    fun `resolver cannot start before matching player teardown acknowledgement`() = runBlocking {
        val barrier = PlaybackTransitionBarrier()
        val ticket = barrier.begin(generation = 12)
        var resolverStarted = false
        val resolver = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.await(ticket)
            resolverStarted = true
        }

        assertFalse(resolverStarted)
        assertFalse(barrier.acknowledge(generation = 11))
        assertFalse(resolverStarted)
        assertTrue(barrier.acknowledge(generation = 12))
        resolver.join()
        assertTrue(resolverStarted)
    }

    @Test
    fun `superseded transition cannot be acknowledged as current`() {
        val barrier = PlaybackTransitionBarrier()
        val displaced = barrier.begin(generation = 20)
        barrier.begin(generation = 21)

        assertTrue(displaced.ready.isCancelled)
        assertFalse(barrier.acknowledge(generation = 20))
        assertTrue(barrier.acknowledge(generation = 21))
    }

    @Test
    fun `late finish from displaced transition does not clear newer ticket`() {
        val barrier = PlaybackTransitionBarrier()
        val displaced = barrier.begin(generation = 30)
        barrier.begin(generation = 31)

        barrier.finish(displaced)

        assertTrue(barrier.acknowledge(generation = 31))
    }

    @Test
    fun `cancel unblocks waiter by cancelling its ticket`() = runBlocking {
        val barrier = PlaybackTransitionBarrier()
        val ticket = barrier.begin(generation = 40)
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            barrier.await(ticket)
        }

        barrier.cancel()
        waiter.join()

        assertTrue(ticket.ready.isCancelled)
        assertTrue(waiter.isCancelled)
        assertFalse(barrier.acknowledge(generation = 40))
    }
}
