package com.miruronative.ui.watch

import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedResumeScriptTest {
    @Test
    fun `resume verifies exact identity before seek and reports matching command acknowledgement`() {
        val script = resumeVideoCommandJs(
            targetSec = 42.5,
            navigationGeneration = 17L,
            capabilityToken = "capability",
            commandId = 9L,
            expectedMediaIdentity = "episode|1440000",
            expectedMediaGeneration = 3L,
        )

        val identityGuard = script.indexOf("__aniliMediaIdentity(video) === expectedMediaIdentity")
        val generationGuard = script.indexOf("__aniliMediaGeneration(video) === expectedMediaGeneration")
        val positionMutation = script.indexOf("video.currentTime = bounded")
        assertTrue(script.contains("expectedMediaIdentity = 'episode|1440000'"))
        assertTrue(script.contains("expectedMediaGeneration = 3"))
        assertTrue(script.contains("findContentVideo() === video"))
        assertTrue(identityGuard >= 0)
        assertTrue(generationGuard >= 0)
        assertTrue(positionMutation > identityGuard)
        assertTrue(positionMutation > generationGuard)
        assertTrue(script.contains("AniliProgress.onCommandResult("))
        assertTrue(script.contains("'capability', '17', '9'"))
    }

    @Test
    fun `resume starts paused video and acknowledges only confirmed playing state`() {
        val script = resumeVideoCommandJs(
            targetSec = 10.0,
            navigationGeneration = 18L,
            capabilityToken = "capability",
            commandId = 10L,
            expectedMediaIdentity = "episode|1440000",
            expectedMediaGeneration = 4L,
        )

        assertTrue(script.contains("video.play()"))
        assertTrue(script.contains("playResult.then"))
        assertTrue(script.contains("!video.paused && !video.ended"))
        assertTrue(script.contains("success = success && matches(video) && playing"))
        assertTrue(script.contains("setTimeout(function() { report(false, video); }, 1800)"))
    }
}
