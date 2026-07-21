package com.miruronative.data.reminder

import com.miruronative.data.model.IncompleteSourceException
import com.miruronative.data.model.SourceCompleteness
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CompleteSnapshotTest {

    @Test
    fun `complete snapshot preserves input order and records explicit absences`() = runBlocking {
        val result = fetchCompleteSnapshot(listOf(3, 1, 2)) { value ->
            if (value == 1) SourceCompleteness.DefinitiveAbsence
            else SourceCompleteness.Present(value * 10)
        }

        assertEquals(listOf(30, 20), result.present)
        assertEquals(1, result.definitiveAbsenceCount)
        assertEquals(3, result.inputCount)
    }

    @Test
    fun `incomplete signal rejects the whole snapshot`() {
        val error = runCatching {
            runBlocking {
                fetchCompleteSnapshot(listOf(1, 2, 3)) { value ->
                    if (value == 2) SourceCompleteness.Incomplete("metadata field missing")
                    else SourceCompleteness.Present(value)
                }
            }
        }.exceptionOrNull()

        assertTrue(error is IncompleteSourceException)
        assertEquals(
            "Release snapshot is incomplete at input 1: metadata field missing",
            error?.message,
        )
    }

    @Test
    fun `fetch exception rejects the snapshot instead of becoming absence`() {
        val error = runCatching {
            runBlocking {
                fetchCompleteSnapshot(listOf(1, 2, 3)) { value ->
                    if (value == 2) error("metadata unavailable")
                    SourceCompleteness.Present(value)
                }
            }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("metadata unavailable", error?.message)
    }

    @Test
    fun `snapshot fetch concurrency is bounded`() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()

        val result = fetchCompleteSnapshot((1..12).toList(), maxConcurrency = 2) { value ->
            val now = active.incrementAndGet()
            peak.updateAndGet { previous -> maxOf(previous, now) }
            try {
                delay(20)
                SourceCompleteness.Present(value)
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals((1..12).toList(), result.present)
        assertTrue("expected parallel work", peak.get() > 1)
        assertTrue("peak=${peak.get()}", peak.get() <= 2)
    }

    @Test
    fun `cancellation is propagated`() {
        try {
            runBlocking {
                fetchCompleteSnapshot<Int, Int>(listOf(1)) {
                    throw CancellationException("stopped")
                }
            }
            fail("Expected cancellation")
        } catch (error: CancellationException) {
            assertEquals("stopped", error.message)
        }
    }
}
