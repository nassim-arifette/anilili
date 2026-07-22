package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            desiredPlaying = true,
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
        assertTrue(script.contains("var desiredPlaying = true"))
        assertTrue(
            script.contains(
                "playbackMutationEpoch = __aniliBeginPlaybackMutation(video, desiredPlaying)",
            ),
        )
        assertTrue(
            script.contains("__aniliPlaybackMutationIsCurrent(video, playbackMutationEpoch)"),
        )
        assertTrue(script.contains("var forwardOnly = false"))
        assertTrue(
            script.contains(
                "window.__aniliPlaybackDesiredPlaying = desiredPlaying === true",
            ),
        )
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
        assertTrue(script.contains("root.addEventListener('play'"))
        assertTrue(script.contains("window.__aniliPlaybackDesiredPlaying !== true"))
        assertTrue(script.contains("shouldPlay = video.paused || video.ended"))
        assertTrue(
            script.contains("playbackMutationEpoch = __aniliBeginPlaybackMutation(video, shouldPlay)"),
        )
        assertTrue(
            script.contains("__aniliPlaybackMutationIsCurrent(video, playbackMutationEpoch)"),
        )
        assertTrue(
            script.contains(
                "window.__aniliPlaybackDesiredPlaying = desiredPlaying === true",
            ),
        )
    }

    @Test
    fun `automatic forward-only seek acknowledges a crossed target without rewinding`() {
        val script = seekVideoCommandJs(
            targetSec = 90.0,
            navigationGeneration = 17,
            capabilityToken = "capability",
            commandId = 10,
            expectedMediaIdentity = "episode|1440000",
            expectedMediaGeneration = 3,
            forwardOnly = true,
            desiredPlaying = true,
        )

        val crossedTargetGuard = script.indexOf(
            "if (forwardOnly && isFinite(video.currentTime) && video.currentTime >= bounded)",
        )
        val positionMutation = script.indexOf("video.currentTime = bounded")
        assertTrue(script.contains("var forwardOnly = true"))
        assertTrue(script.contains("? video.currentTime + 1.5 >= bounded"))
        assertTrue(crossedTargetGuard >= 0)
        assertTrue(positionMutation > crossedTargetGuard)
        assertTrue(script.substring(crossedTargetGuard, positionMutation).contains("confirmSeek();"))
    }

    @Test
    fun `suppressed manual seek keeps native pause intent despite provider DOM playback`() {
        val script = seekVideoCommandJs(
            targetSec = 42.0,
            navigationGeneration = 22,
            capabilityToken = "capability",
            commandId = 11,
            expectedMediaIdentity = "episode|1440000",
            expectedMediaGeneration = 4,
            desiredPlaying = false,
        )

        val pauseAll = script.indexOf("if (!desiredPlaying) __aniliPauseAllMedia()")
        val seek = script.indexOf("video.currentTime = bounded")
        assertTrue(script.contains("var desiredPlaying = false"))
        assertFalse(script.contains("shouldRemainPlaying = !video.paused"))
        assertTrue(
            script.contains(
                "playbackMutationEpoch = __aniliBeginPlaybackMutation(video, desiredPlaying)",
            ),
        )
        assertTrue(script.contains("(desiredPlaying ? playing : !playing)"))
        assertTrue(pauseAll >= 0)
        assertTrue(seek > pauseAll)
    }

    @Test
    fun `javascript string literal escapes every line terminator`() {
        val literal = "a\\b'c\r\nd\u2028e\u2029f".toJsStringLiteral()

        assertEquals("'a\\\\b\\'c\\r\\nd\\u2028e\\u2029f'", literal)
        assertFalse(literal.contains('\r'))
        assertFalse(literal.contains('\n'))
        assertFalse(literal.contains('\u2028'))
        assertFalse(literal.contains('\u2029'))
    }
}
