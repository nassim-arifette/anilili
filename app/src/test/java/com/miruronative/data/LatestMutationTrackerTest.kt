package com.miruronative.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestMutationTrackerTest {
    @Test
    fun `an older completion cannot clear a newer mutation for the same key`() {
        val tracker = LatestMutationTracker<Int>()
        val first = tracker.begin(listOf(7))
        val last = tracker.begin(listOf(7))

        tracker.complete(first)

        assertFalse(tracker.isLatest(first))
        assertTrue(tracker.isLatest(last))
        assertTrue(7 in tracker.pendingKeys())
    }

    @Test
    fun `unrelated keys complete independently`() {
        val tracker = LatestMutationTracker<String>()
        val first = tracker.begin(listOf("autoplay"))
        val second = tracker.begin(listOf("quality"))

        tracker.complete(first)

        assertFalse("autoplay" in tracker.pendingKeys())
        assertTrue("quality" in tracker.pendingKeys())
        assertTrue(tracker.isLatest(second))
    }

    @Test
    fun `durable acknowledgement clears only its exact ticket`() {
        val tracker = LatestMutationTracker<String>()
        val old = tracker.begin(listOf("autoplay"))
        val latest = tracker.begin(listOf("autoplay"))

        tracker.acknowledge(old.persistenceToken)

        assertTrue(tracker.isLatest(latest))
        tracker.acknowledge(latest.persistenceToken)
        assertFalse("autoplay" in tracker.pendingKeys())
    }
}
