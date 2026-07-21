package com.miruronative.ui.watch

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedLifecyclePolicyTest {
    @Test
    fun `paused owner cannot resume embed after blank commit`() {
        assertFalse(shouldResumeEmbedForLifecycleState(Lifecycle.State.STARTED))
        assertFalse(shouldResumeEmbedForLifecycleEvent(Lifecycle.Event.ON_START))
    }

    @Test
    fun `resumed owner may resume embed`() {
        assertTrue(shouldResumeEmbedForLifecycleState(Lifecycle.State.RESUMED))
        assertTrue(shouldResumeEmbedForLifecycleEvent(Lifecycle.Event.ON_RESUME))
    }

    @Test
    fun `ownerless host keeps existing fallback behavior`() {
        assertTrue(shouldResumeEmbedForLifecycleState(null))
    }
}
