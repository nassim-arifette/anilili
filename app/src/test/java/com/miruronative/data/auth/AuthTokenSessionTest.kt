package com.miruronative.data.auth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTokenSessionTest {

    @Test
    fun `callback token stays bound to the login that issued its state`() {
        val session = AuthTokenSession("existing")
        session.beginLogin("state-a")
        val callbackA = requireNotNull(session.claimToken("state-a", "token-a"))

        session.beginLogin("state-b")

        assertEquals("token-a", callbackA.token)
        assertNull(session.claimToken("state-a", "late-token-a"))
        assertEquals("token-b", session.claimToken("state-b", "token-b")?.token)
    }

    @Test
    fun `stale callback cannot overwrite a newer successful login`() {
        val persistedToken = AtomicReference<String?>("existing")
        val session = AuthTokenSession("existing")
        session.beginLogin("state-a")
        val callbackA = requireNotNull(session.claimToken("state-a", "token-a"))
        session.beginLogin("state-b")
        val callbackB = requireNotNull(session.claimToken("state-b", "token-b"))

        assertTrue(session.replaceLoginIfCurrent(callbackB, persistedToken::set))
        assertFalse(session.replaceLoginIfCurrent(callbackA, persistedToken::set))

        assertEquals("token-b", persistedToken.get())
        assertEquals("token-b", session.current(isExpired = { false }, clearExpired = {}))
    }

    @Test
    fun `logout invalidates an already claimed callback`() {
        val persistedToken = AtomicReference<String?>("existing")
        val session = AuthTokenSession("existing")
        session.beginLogin("state")
        val callback = requireNotNull(session.claimToken("state", "late-token"))

        session.replace(null, persistedToken::set)

        assertFalse(session.replaceLoginIfCurrent(callback, persistedToken::set))
        assertNull(persistedToken.get())
        assertNull(session.current(isExpired = { false }, clearExpired = {}))
    }

    @Test
    fun `session generation publication is atomic with replacement`() {
        val session = AuthTokenSession("token-a")
        val generation = requireNotNull(session.authenticatedGeneration())
        var published = false

        assertTrue(session.commitIfGenerationCurrent(generation) { published = true })
        session.replace("token-b") {}
        assertFalse(session.commitIfGenerationCurrent(generation) { error("stale publication") })

        assertTrue(published)
    }

    @Test
    fun `logged out session has no authenticated generation`() {
        val session = AuthTokenSession()

        assertNull(session.authenticatedGeneration())
        session.replace("token") {}
        assertTrue(session.authenticatedGeneration() != null)
        session.replace(null) {}
        assertNull(session.authenticatedGeneration())
    }

    @Test
    fun `logged out publication is rejected after login wins`() {
        val session = AuthTokenSession()
        var publication = "none"

        assertTrue(session.commitIfLoggedOut { publication = "logged-out" })
        session.beginLogin("state")
        val callback = requireNotNull(session.claimToken("state", "token"))
        assertTrue(session.replaceLoginIfCurrent(callback) {})
        assertFalse(session.commitIfLoggedOut { publication = "stale" })

        assertEquals("logged-out", publication)
    }

    @Test
    fun `concurrent duplicate callback writes allow exactly one winner`() {
        val session = AuthTokenSession()
        session.beginLogin("state")
        val callbackA = requireNotNull(session.claimToken("state", "token-a"))
        val callbackB = requireNotNull(session.claimToken("state", "token-b"))
        val commits = AtomicInteger(0)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val results = listOf(callbackA, callbackB).map { callback ->
                executor.submit<Boolean> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    session.replaceLoginIfCurrent(callback) { commits.incrementAndGet() }
                }
            }
            start.countDown()

            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, commits.get())
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `stale expiry check cannot erase a newly installed token`() {
        val persistedToken = AtomicReference<String?>("expired")
        val expiredCleanupCount = AtomicInteger(0)
        val expiryCheckStarted = CountDownLatch(1)
        val finishExpiryCheck = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val session = AuthTokenSession("expired")

        try {
            val current = executor.submit<String?> {
                session.current(
                    isExpired = { token ->
                        if (token == "expired") {
                            expiryCheckStarted.countDown()
                            assertTrue(finishExpiryCheck.await(5, TimeUnit.SECONDS))
                            true
                        } else {
                            false
                        }
                    },
                    clearExpired = {
                        expiredCleanupCount.incrementAndGet()
                        persistedToken.set(null)
                    },
                )
            }

            assertTrue(expiryCheckStarted.await(5, TimeUnit.SECONDS))
            session.replace("fresh", persistedToken::set)
            finishExpiryCheck.countDown()

            assertEquals("fresh", current.get(5, TimeUnit.SECONDS))
            assertEquals("fresh", persistedToken.get())
            assertEquals(0, expiredCleanupCount.get())
        } finally {
            finishExpiryCheck.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `current generation clears an expired token once`() {
        val persistedToken = AtomicReference<String?>("expired")
        val expiredCleanupCount = AtomicInteger(0)
        val session = AuthTokenSession("expired")

        val current = session.current(
            isExpired = { true },
            clearExpired = {
                expiredCleanupCount.incrementAndGet()
                persistedToken.set(null)
            },
        )

        assertNull(current)
        assertNull(persistedToken.get())
        assertEquals(1, expiredCleanupCount.get())
        assertNull(session.current(isExpired = { false }, clearExpired = {}))
    }

    @Test
    fun `expired previous account cleanup preserves the pending login`() {
        val session = AuthTokenSession("expired")
        session.beginLogin("new-state")

        assertNull(session.current(isExpired = { true }, clearExpired = {}))
        val callback = requireNotNull(session.claimToken("new-state", "fresh"))

        assertTrue(session.replaceLoginIfCurrent(callback) {})
        assertEquals("fresh", session.current(isExpired = { false }, clearExpired = {}))
    }

    @Test
    fun `invalid callback input never claims the pending login`() {
        val session = AuthTokenSession()
        session.beginLogin("expected")

        assertNull(session.claimToken("wrong", "token"))
        assertNull(session.claimToken("expected", " "))
        assertNull(session.claimToken(null, "token"))
    }
}
