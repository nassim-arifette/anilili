package com.miruronative.data.auth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTokenSessionTest {

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
            session.replace("fresh") { persistedToken.set("fresh") }
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
}
