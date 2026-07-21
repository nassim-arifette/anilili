package com.miruronative.data.remote

import kotlinx.coroutines.async
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
        val oldReadiness = requireNotNull(lifecycle.readinessSignal(attachment.session))
        val newerNavigation = requireNotNull(lifecycle.beginNavigation(attachment.session))

        assertFalse(
            lifecycle.markReady(
                attachment.session,
                attachment.readinessGeneration,
                successful = true,
            ),
        )
        assertFalse(oldReadiness.result.await())
        assertTrue(
            lifecycle.markReady(
                attachment.session,
                newerNavigation.readinessGeneration,
                successful = true,
            ),
        )
        assertTrue(requireNotNull(lifecycle.readinessSignal(attachment.session)).result.await())
    }

    @Test
    fun `fetch started before WebView attach waits for the next session`() = runBlocking {
        val lifecycle = PipeRequestLifecycle<String>()
        val waiting = async { lifecycle.awaitSession() }

        assertFalse(waiting.isCompleted)
        val attachment = lifecycle.attach()

        assertSame(attachment.session, waiting.await())
    }

    @Test
    fun `readiness failure is terminal and blocks request registration`() = runBlocking {
        val lifecycle = PipeRequestLifecycle<String>()
        val attachment = lifecycle.attach()
        val session = attachment.session
        val readiness = requireNotNull(lifecycle.readinessSignal(session))

        assertTrue(lifecycle.markReady(session, attachment.readinessGeneration, successful = false))
        assertFalse(readiness.result.await())
        assertFalse(lifecycle.markReady(session, attachment.readinessGeneration, successful = true))
        assertNull(lifecycle.register(readiness, "request"))
    }

    @Test
    fun `cancelled request is removed and a late result is ignored`() {
        val lifecycle = PipeRequestLifecycle<String>()
        val attachment = lifecycle.attach()
        val session = attachment.session
        val readiness = requireNotNull(lifecycle.readinessSignal(session))
        lifecycle.markReady(session, attachment.readinessGeneration, successful = true)
        val request = requireNotNull(lifecycle.register(readiness, "request"))

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
        val oldReadiness = requireNotNull(lifecycle.readinessSignal(oldSession))
        lifecycle.markReady(oldSession, oldAttachment.readinessGeneration, successful = true)
        val oldRequest = requireNotNull(lifecycle.register(oldReadiness, "old"))

        val replacement = lifecycle.attach()

        assertEquals(listOf(oldRequest), replacement.displacedRequests)
        assertFalse(lifecycle.isCurrent(oldSession))
        assertNull(lifecycle.register(oldReadiness, "late-old"))
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
        val readiness = requireNotNull(lifecycle.readinessSignal(readySession))
        lifecycle.markReady(readySession, readyAttachment.readinessGeneration, successful = true)
        val first = requireNotNull(lifecycle.register(readiness, "first"))
        val second = requireNotNull(lifecycle.register(readiness, "second"))

        assertEquals(listOf(first, second), lifecycle.detach(readySession))
        assertFalse(lifecycle.isPending(first))
        assertFalse(lifecycle.isPending(second))
    }

    @Test
    fun `result can only claim a request once`() {
        val lifecycle = PipeRequestLifecycle<String>()
        val attachment = lifecycle.attach()
        val session = attachment.session
        val readiness = requireNotNull(lifecycle.readinessSignal(session))
        lifecycle.markReady(session, attachment.readinessGeneration, successful = true)
        val request = requireNotNull(lifecycle.register(readiness, "request"))

        assertSame(request, lifecycle.take("request"))
        assertNull(lifecycle.take("request"))
    }

    @Test
    fun `navigation after ready drains old document requests and resets readiness`() = runBlocking {
        val lifecycle = PipeRequestLifecycle<String>()
        val attachment = lifecycle.attach()
        val oldSignal = requireNotNull(lifecycle.readinessSignal(attachment.session))
        lifecycle.markReady(
            attachment.session,
            attachment.readinessGeneration,
            successful = true,
        )
        val oldRequest = requireNotNull(lifecycle.register(oldSignal, "old-document"))

        val navigation = requireNotNull(lifecycle.beginNavigation(attachment.session))
        val newSignal = requireNotNull(lifecycle.readinessSignal(attachment.session))

        assertEquals(listOf(oldRequest), navigation.displacedRequests)
        assertFalse(lifecycle.isCurrent(oldSignal))
        assertFalse(newSignal.result.isCompleted)
        assertNull(lifecycle.register(oldSignal, "late-old-document"))
        assertTrue(
            lifecycle.markReady(
                attachment.session,
                navigation.readinessGeneration,
                successful = true,
            ),
        )
        assertTrue(newSignal.result.await())
        assertTrue(lifecycle.register(newSignal, "new-document") != null)
    }
}
