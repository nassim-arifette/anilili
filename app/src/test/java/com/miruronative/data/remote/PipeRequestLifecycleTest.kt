package com.miruronative.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PipeRequestLifecycleTest {

    @Test
    fun `stale page completion cannot mark replacement ready`() = runBlocking {
        val lifecycle = PipeRequestLifecycle<String>()
        val oldAttachment = lifecycle.attach()
        val oldSession = oldAttachment.session
        val replacementAttachment = lifecycle.attach()
        val replacement = replacementAttachment.session

        assertFalse(oldSession.readiness.await())
        assertFalse(
            lifecycle.markReady(oldSession, oldAttachment.readinessGeneration, successful = true),
        )
        assertFalse(replacement.readiness.isCompleted)

        assertTrue(
            lifecycle.markReady(
                replacement,
                replacementAttachment.readinessGeneration,
                successful = true,
            ),
        )
        assertTrue(replacement.readiness.await())
    }

    @Test
    fun `page completion from an earlier navigation cannot mark the session ready`() = runBlocking {
        val lifecycle = PipeRequestLifecycle<String>()
        val attachment = lifecycle.attach()
        val newerNavigation = requireNotNull(lifecycle.advanceReadinessGeneration(attachment.session))

        assertFalse(
            lifecycle.markReady(
                attachment.session,
                attachment.readinessGeneration,
                successful = true,
            ),
        )
        assertFalse(attachment.session.readiness.isCompleted)
        assertTrue(lifecycle.markReady(attachment.session, newerNavigation, successful = true))
        assertTrue(attachment.session.readiness.await())
    }

    @Test
    fun `readiness failure is terminal and blocks request registration`() = runBlocking {
        val lifecycle = PipeRequestLifecycle<String>()
        val attachment = lifecycle.attach()
        val session = attachment.session

        assertTrue(lifecycle.markReady(session, attachment.readinessGeneration, successful = false))
        assertFalse(session.readiness.await())
        assertFalse(lifecycle.markReady(session, attachment.readinessGeneration, successful = true))
        assertNull(lifecycle.register(session, "request"))
    }

    @Test
    fun `cancelled request is removed and a late result is ignored`() {
        val lifecycle = PipeRequestLifecycle<String>()
        val attachment = lifecycle.attach()
        val session = attachment.session
        lifecycle.markReady(session, attachment.readinessGeneration, successful = true)
        val request = requireNotNull(lifecycle.register(session, "request"))

        assertTrue(lifecycle.isPending(request))
        assertTrue(lifecycle.cancel(request))
        assertFalse(lifecycle.isPending(request))
        assertNull(lifecycle.take(request.id))
    }

    @Test
    fun `replacement drains old requests and rejects their generation`() = runBlocking {
        val lifecycle = PipeRequestLifecycle<String>()
        val oldAttachment = lifecycle.attach()
        val oldSession = oldAttachment.session
        lifecycle.markReady(oldSession, oldAttachment.readinessGeneration, successful = true)
        val oldRequest = requireNotNull(lifecycle.register(oldSession, "old"))

        val replacement = lifecycle.attach()

        assertEquals(listOf(oldRequest), replacement.displacedRequests)
        assertFalse(lifecycle.isCurrent(oldSession))
        assertNull(lifecycle.register(oldSession, "late-old"))
        assertFalse(lifecycle.cancel(oldRequest))
        assertFalse(replacement.session.readiness.isCompleted)
    }

    @Test
    fun `stale detach cannot invalidate the current generation`() = runBlocking {
        val lifecycle = PipeRequestLifecycle<String>()
        val oldSession = lifecycle.attach().session
        val currentAttachment = lifecycle.attach()
        val currentSession = currentAttachment.session

        assertTrue(lifecycle.detach(oldSession).isEmpty())
        assertTrue(lifecycle.isCurrent(currentSession))
        assertTrue(
            lifecycle.markReady(
                currentSession,
                currentAttachment.readinessGeneration,
                successful = true,
            ),
        )
        assertTrue(currentSession.readiness.await())
    }

    @Test
    fun `detach wakes readiness waiters and drains active requests`() = runBlocking {
        val lifecycle = PipeRequestLifecycle<String>()
        val waitingSession = lifecycle.attach().session

        assertTrue(lifecycle.detach(waitingSession).isEmpty())
        assertFalse(waitingSession.readiness.await())

        val readyAttachment = lifecycle.attach()
        val readySession = readyAttachment.session
        lifecycle.markReady(readySession, readyAttachment.readinessGeneration, successful = true)
        val first = requireNotNull(lifecycle.register(readySession, "first"))
        val second = requireNotNull(lifecycle.register(readySession, "second"))

        assertEquals(listOf(first, second), lifecycle.detach(readySession))
        assertFalse(lifecycle.isPending(first))
        assertFalse(lifecycle.isPending(second))
    }

    @Test
    fun `result can only claim a request once`() {
        val lifecycle = PipeRequestLifecycle<String>()
        val attachment = lifecycle.attach()
        val session = attachment.session
        lifecycle.markReady(session, attachment.readinessGeneration, successful = true)
        val request = requireNotNull(lifecycle.register(session, "request"))

        assertSame(request, lifecycle.take("request"))
        assertNull(lifecycle.take("request"))
    }
}
