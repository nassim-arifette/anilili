package com.miruronative.data.reminder

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CompleteSnapshotTest {

    @Test
    fun `complete snapshot preserves input order`() = runBlocking {
        val result = fetchCompleteSnapshot(listOf(3, 1, 2)) { it * 10 }

        assertEquals(listOf(30, 10, 20), result)
    }

    @Test
    fun `successful null result is omitted`() = runBlocking {
        val result = fetchCompleteSnapshot(listOf(1, 2, 3)) { value ->
            value.takeUnless { it == 2 }
        }

        assertEquals(listOf(1, 3), result)
    }

    @Test
    fun `one failure rejects the partial snapshot`() {
        val error = runCatching {
            runBlocking {
                fetchCompleteSnapshot(listOf(1, 2, 3)) { value ->
                    if (value == 2) error("metadata unavailable")
                    value
                }
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("metadata unavailable", error?.message)
    }

    @Test
    fun `cancellation is propagated`() {
        try {
            runBlocking {
                fetchCompleteSnapshot(listOf(1)) { throw CancellationException("stopped") }
            }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("stopped", error.message)
        }
    }
}
