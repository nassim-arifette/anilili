package com.miruronative.ui.watch

import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedVideoMutationScriptTest {
    @Test
    fun `playback speed rejects a video outside the exact concrete media generation`() {
        val script = setPlaybackSpeedJs(
            speed = 1.5f,
            navigationGeneration = 17L,
            expectedMediaIdentity = "episode|1440000",
            expectedMediaGeneration = 3L,
        )

        val identityGuard = script.indexOf("__aniliMediaIdentity(v) !== expectedMediaIdentity")
        val generationGuard = script.indexOf("__aniliMediaGeneration(v) !== expectedMediaGeneration")
        val speedMutation = script.indexOf("v.playbackRate = 1.5")
        assertTrue(script.contains("expectedMediaIdentity = 'episode|1440000'"))
        assertTrue(script.contains("expectedMediaGeneration = 3"))
        assertTrue(identityGuard >= 0)
        assertTrue(generationGuard >= 0)
        assertTrue(speedMutation > identityGuard)
        assertTrue(speedMutation > generationGuard)
    }

    @Test
    fun `web volume rejects a video outside the exact concrete media generation`() {
        val script = webVolumeJs(
            delta = 0.1f,
            absolute = null,
            navigationGeneration = 18L,
            expectedMediaIdentity = "episode|1440000",
            expectedMediaGeneration = 4L,
        )

        val identityGuard = script.indexOf("__aniliMediaIdentity(v) !== expectedMediaIdentity")
        val generationGuard = script.indexOf("__aniliMediaGeneration(v) !== expectedMediaGeneration")
        val volumeMutation = script.indexOf("v.volume = Math.max")
        assertTrue(identityGuard >= 0)
        assertTrue(generationGuard >= 0)
        assertTrue(volumeMutation > identityGuard)
        assertTrue(volumeMutation > generationGuard)
    }
}
