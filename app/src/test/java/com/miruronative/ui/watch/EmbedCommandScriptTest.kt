package com.miruronative.ui.watch

import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedCommandScriptTest {
    @Test
    fun `seek reports asynchronous command acknowledgement for selected content`() {
        val script = seekVideoCommandJs(
            targetSec = 42.5,
            navigationGeneration = 17,
            capabilityToken = "capability",
            commandId = 9,
            expectedMediaIdentity = "episode|1440000",
            expectedMediaGeneration = 3,
        )

        assertTrue(script.contains("findContentVideo()"))
        assertTrue(script.contains("addEventListener('seeked'"))
        assertTrue(script.contains("AniliProgress.onCommandResult("))
        assertTrue(script.contains("'capability', '17', '9'"))
        assertTrue(script.contains("Math.abs(video.currentTime - bounded) <= 1.5"))
        assertTrue(script.contains("expectedMediaIdentity = 'episode|1440000'"))
        assertTrue(script.contains("expectedMediaGeneration = 3"))
        assertTrue(script.contains("__aniliMediaGeneration(video)"))
        assertTrue(script.contains("findContentVideo() === video"))
    }

    @Test
    fun `toggle waits for play promise before reporting confirmed state`() {
        val script = togglePlaybackCommandJs(18, "capability", 10)

        assertTrue(script.contains("findContentVideo()"))
        assertTrue(script.contains("playResult.then"))
        assertTrue(script.contains("catch(function() { report(false, video); })"))
        assertTrue(script.contains("playing = !!video && !video.paused"))
        assertTrue(script.contains("'capability', '18', '10'"))
        assertTrue(script.contains("__aniliPauseCompetingMedia(video)"))
        assertTrue(script.contains("__aniliPauseAllMedia()"))
    }
}
