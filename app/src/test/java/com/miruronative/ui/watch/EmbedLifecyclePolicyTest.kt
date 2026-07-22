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

    @Test
    fun `navigation request clamps pending play intent to actual lifecycle state`() {
        assertFalse(
            embedNavigationDesiredPlaying(
                pendingDesiredPlaying = true,
                lifecycleState = Lifecycle.State.STARTED,
            ),
        )
        assertTrue(
            embedNavigationDesiredPlaying(
                pendingDesiredPlaying = true,
                lifecycleState = Lifecycle.State.RESUMED,
            ),
        )
        assertFalse(
            embedNavigationDesiredPlaying(
                pendingDesiredPlaying = false,
                lifecycleState = Lifecycle.State.RESUMED,
            ),
        )
    }

    @Test
    fun `new paused generation stays silent through resume until explicit user intent`() {
        val requestDesiredPlaying = embedNavigationDesiredPlaying(
            pendingDesiredPlaying = true,
            lifecycleState = Lifecycle.State.STARTED,
        )
        assertFalse(requestDesiredPlaying)

        // ON_RESUME makes the WebView active again but deliberately retains the generation latch.
        val automaticResumeSuppressed = true
        val firstResumedTick = planEmbedLifecycleTick(
            reportedPlaying = true,
            lifecyclePlaybackAllowed = true,
            automaticResumeSuppressed = automaticResumeSuppressed,
        )
        assertFalse(firstResumedTick.acceptPlaying)
        assertTrue(firstResumedTick.reassertPause)
        assertFalse(
            canDispatchEmbedResume(
                lifecyclePlaybackAllowed = true && !automaticResumeSuppressed,
                controlsReady = true,
                navigationCurrent = true,
            ),
        )

        val tickAfterExplicitPlay = planEmbedLifecycleTick(
            reportedPlaying = true,
            lifecyclePlaybackAllowed = true,
            automaticResumeSuppressed = false,
        )
        assertTrue(tickAfterExplicitPlay.acceptPlaying)
        assertFalse(tickAfterExplicitPlay.reassertPause)
    }
}
