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
        assertFalse(
            canDispatchEmbedResume(
                lifecyclePlaybackAllowed = false,
                controlsReady = true,
                navigationCurrent = true,
            ),
        )
    }

    @Test
    fun `resumed owner may resume embed`() {
        assertTrue(shouldResumeEmbedForLifecycleState(Lifecycle.State.RESUMED))
        assertTrue(shouldResumeEmbedForLifecycleEvent(Lifecycle.Event.ON_RESUME))
        assertTrue(
            canDispatchEmbedResume(
                lifecyclePlaybackAllowed = true,
                controlsReady = true,
                navigationCurrent = true,
            ),
        )
    }

    @Test
    fun `resume dispatch also requires controls and current navigation`() {
        assertFalse(
            canDispatchEmbedResume(
                lifecyclePlaybackAllowed = true,
                controlsReady = false,
                navigationCurrent = true,
            ),
        )
        assertFalse(
            canDispatchEmbedResume(
                lifecyclePlaybackAllowed = true,
                controlsReady = true,
                navigationCurrent = false,
            ),
        )
    }

    @Test
    fun `ownerless host keeps existing fallback behavior`() {
        assertTrue(shouldResumeEmbedForLifecycleState(null))
    }
}
