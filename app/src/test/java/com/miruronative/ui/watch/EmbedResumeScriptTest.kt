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
    fun `resume starts paused video and acknowledges only its requested playing state`() {
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
        assertTrue(script.contains("var desiredPlaying = true"))
        assertTrue(script.contains("matchesDesiredPlaybackState(video)"))
        assertTrue(
            script.contains(
                "matchesDesiredPlaybackState(video) && remainsAtOrBeyondTarget(video)",
            ),
        )
        assertTrue(script.contains("video.currentTime + 1.5 >= bounded"))
        val finalPositionProof = script.indexOf("remainsAtOrBeyondTarget(video);", startIndex = 0)
        val acknowledgement = script.indexOf("AniliProgress.onCommandResult(")
        assertTrue(finalPositionProof >= 0)
        assertTrue(acknowledgement > finalPositionProof)
        assertTrue(script.contains("setTimeout(function() { report(false, video); }, 1800)"))
    }

    @Test
    fun `later playback mutation makes delayed resume callbacks inert`() {
        val script = resumeVideoCommandJs(
            targetSec = 42.0,
            navigationGeneration = 19L,
            capabilityToken = "capability",
            commandId = 11L,
            expectedMediaIdentity = "episode|1440000",
            expectedMediaGeneration = 5L,
        )

        val claim = script.indexOf(
            "playbackMutationEpoch = __aniliBeginPlaybackMutation(video, desiredPlaying)",
        )
        val delayedGuard = script.indexOf("!stillOwnsPlaybackMutation(video)")
        val playMutation = script.indexOf("var playResult = video.play()")
        assertTrue(script.contains("window.__aniliPlaybackMutationState"))
        assertTrue(script.contains("current.epoch === epoch"))
        assertTrue(claim >= 0)
        assertTrue(delayedGuard >= 0)
        assertTrue(playMutation > delayedGuard)
        assertTrue(
            script.contains(
                "__aniliPlaybackMutationIsCurrent(video, playbackMutationEpoch)",
            ),
        )
        assertTrue(script.contains("playResult.then(settleResumePlay)"))
        assertTrue(script.contains("__aniliReconcileStaleResumePlay(video, playbackMutationEpoch)"))
    }

    @Test
    fun `paused restore pauses every reachable media before seeking and acknowledging`() {
        val script = resumeVideoCommandJs(
            targetSec = 42.0,
            navigationGeneration = 21L,
            capabilityToken = "capability",
            commandId = 13L,
            expectedMediaIdentity = "episode-a|1440000",
            expectedMediaGeneration = 7L,
            desiredPlaying = false,
        )

        val mutationClaim = script.indexOf(
            "playbackMutationEpoch = __aniliBeginPlaybackMutation(video, desiredPlaying)",
        )
        val initialPauseAll = script.indexOf("if (!desiredPlaying) __aniliPauseAllMedia()")
        val positionMutation = script.indexOf("video.currentTime = bounded")
        assertTrue(script.contains("var desiredPlaying = false"))
        assertTrue(script.contains("function ensurePaused(video)"))
        assertTrue(script.contains("if (desiredPlaying) ensurePlaying(video); else ensurePaused(video)"))
        assertTrue(script.contains("report(video.paused || video.ended, video)"))
        assertTrue(mutationClaim >= 0)
        assertTrue(initialPauseAll > mutationClaim)
        assertTrue(positionMutation > initialPauseAll)
        assertTrue(script.indexOf("__aniliPauseAllMedia();", initialPauseAll + 1) > initialPauseAll)
    }

    @Test
    fun `stale play promise pauses replaced video unless newer exact intent wants playing`() {
        val script = resumeVideoCommandJs(
            targetSec = 42.0,
            navigationGeneration = 20L,
            capabilityToken = "capability",
            commandId = 12L,
            expectedMediaIdentity = "episode-a|1440000",
            expectedMediaGeneration = 6L,
        )

        assertTrue(script.contains("var selected = findContentVideo()"))
        assertTrue(script.contains("current.epoch > staleEpoch"))
        assertTrue(script.contains("current.desiredPlaying === true"))
        assertTrue(script.contains("current.mediaIdentity === __aniliMediaIdentity(video)"))
        assertTrue(script.contains("current.mediaGeneration === __aniliMediaGeneration(video)"))
        assertTrue(script.contains("if (selected === video && newerExactMutationWantsPlaying) return"))
        assertTrue(script.contains("try { video.pause(); }"))
    }
}
