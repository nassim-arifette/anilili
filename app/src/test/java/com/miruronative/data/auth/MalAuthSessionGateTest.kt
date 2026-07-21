package com.miruronative.data.auth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MalAuthSessionGateTest {

    @Test
    fun `callback keeps its own code and verifier when another login starts before exchange`() {
        val gate = MalAuthSessionGate()
        gate.beginLogin("state-a", "verifier-a")
        val callbackA = requireNotNull(gate.claimCode("state-a", "code-a"))

        gate.beginLogin("state-b", "verifier-b")

        assertEquals("code-a", callbackA.code)
        assertEquals("verifier-a", callbackA.verifier)
        assertFalse(gate.isCurrent(callbackA))
        assertEquals("verifier-b", gate.claimCode("state-b", "code-b")?.verifier)
    }

    @Test
    fun `stale callback failure cannot clear a newer pending login`() {
        val gate = MalAuthSessionGate()
        gate.beginLogin("state-a", "verifier-a")
        val callbackA = requireNotNull(gate.claimCode("state-a", "code-a"))

        gate.beginLogin("state-b", "verifier-b")
        val cleared = gate.replaceLoginIfCurrent(callbackA) {}

        assertFalse(cleared)
        assertEquals("verifier-b", gate.claimCode("state-b", "code-b")?.verifier)
    }

    @Test
    fun `concurrent replacement and old failure always preserve the newer login`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val gate = MalAuthSessionGate()
                gate.beginLogin("state-a", "verifier-a")
                val callbackA = requireNotNull(gate.claimCode("state-a", "code-a"))
                val start = CountDownLatch(1)

                val replacement = executor.submit {
                    start.await()
                    gate.beginLogin("state-b", "verifier-b")
                }
                val oldFailure = executor.submit<Boolean> {
                    start.await()
                    gate.replaceLoginIfCurrent(callbackA) {}
                }

                start.countDown()
                replacement.get(5, TimeUnit.SECONDS)
                oldFailure.get(5, TimeUnit.SECONDS)

                assertEquals("verifier-b", gate.claimCode("state-b", "code-b")?.verifier)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `invalid callback input never claims the pending verifier`() {
        val gate = MalAuthSessionGate()
        gate.beginLogin("expected", "secret-verifier")

        assertNull(gate.claimCode("wrong", "code"))
        assertNull(gate.claimCode("expected", ""))
        assertNull(gate.claimCode(null, "code"))
    }
}
