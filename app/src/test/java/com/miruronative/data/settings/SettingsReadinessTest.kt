package com.miruronative.data.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SettingsReadinessTest {
    @Test
    fun `waiter does not continue before persisted settings are loaded`() = runBlocking {
        val readiness = MutableStateFlow(false)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            awaitSettingsReady(readiness)
        }

        yield()
        assertFalse(waiter.isCompleted)
        readiness.value = true

        waiter.await()
        assertTrue(waiter.isCompleted)
    }

    @Test
    fun `waiter preserves cancellation`() = runBlocking {
        val readiness = MutableStateFlow(false)
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            awaitSettingsReady(readiness)
        }

        waiter.cancel(CancellationException("stopped"))
        try {
            waiter.await()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: readiness must not turn cancellation into a loaded/default state.
        }
    }
}
