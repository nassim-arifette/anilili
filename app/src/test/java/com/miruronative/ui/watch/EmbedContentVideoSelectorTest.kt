package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedContentVideoSelectorTest {
    private fun candidate(
        id: String,
        duration: Double = 1_440.0,
        pixels: Long = 1_024L * 576L,
        visible: Long = pixels,
        playing: Boolean = true,
        ad: Boolean = false,
    ) = EmbedVideoCandidate(id, duration, pixels, visible, 4, true, playing, ad)

    @Test
    fun `short preroll is rejected in favour of episode`() {
        val result = selectEmbedContentVideo(
            listOf(candidate("ad", duration = 30.0), candidate("episode")),
            lockedId = null,
        )

        assertEquals("episode", result?.id)
    }

    @Test
    fun `ad-labelled long video is rejected`() {
        val result = selectEmbedContentVideo(
            listOf(candidate("advert", duration = 600.0, ad = true), candidate("episode")),
            lockedId = null,
        )

        assertEquals("episode", result?.id)
    }

    @Test
    fun `valid lock survives a marginal challenger`() {
        val locked = candidate("episode", pixels = 1_920L * 1_080L)
        val challenger = candidate("overlay", duration = 1_500.0, pixels = 1_920L * 1_080L)

        assertEquals("episode", selectEmbedContentVideo(listOf(locked, challenger), "episode")?.id)
    }

    @Test
    fun `playing plausible video replaces an unverified paused DOM first lock`() {
        val domFirst = candidate("dom-first", playing = false)
        val userStarted = candidate("episode", playing = true)

        assertEquals(
            "episode",
            selectEmbedContentVideo(
                listOf(domFirst, userStarted),
                lockedId = "dom-first",
                lockedVerified = false,
            )?.id,
        )
    }

    @Test
    fun `verified paused sub lock rejects later equal background dub playback`() {
        val establishedSub = candidate("sub", playing = false)
        val backgroundDub = candidate("dub", playing = true)

        assertEquals(
            "sub",
            selectEmbedContentVideo(
                listOf(establishedSub, backgroundDub),
                lockedId = "sub",
                lockedVerified = true,
            )?.id,
        )
    }

    @Test
    fun `verified 720p sub lock rejects later higher score 1080p background dub`() {
        val establishedSub = candidate("sub", pixels = 1_280L * 720L, playing = false)
        val backgroundDub = candidate(
            "dub",
            duration = 1_800.0,
            pixels = 1_920L * 1_080L,
            playing = true,
        )

        assertEquals(
            "sub",
            selectEmbedContentVideo(
                listOf(establishedSub, backgroundDub),
                lockedId = "sub",
                lockedVerified = true,
            )?.id,
        )
    }

    @Test
    fun `invalid lock is replaced and all invalid candidates return null`() {
        assertEquals(
            "episode",
            selectEmbedContentVideo(
                listOf(candidate("ad", duration = 15.0), candidate("episode")),
                "ad",
            )?.id,
        )
        assertNull(selectEmbedContentVideo(listOf(candidate("ad", duration = 15.0)), null))
    }

    @Test
    fun `browser selector scans all videos and keeps a content lock`() {
        val script = embedContentVideoSelectorJs()

        assertTrue(script.contains("__aniliCollectMedia(root, 'video', out)"))
        assertTrue(script.contains("__aniliLooksLikeAd"))
        assertTrue(script.contains("duration < 120.0"))
        assertTrue(script.contains("window.__aniliContentVideo"))
        assertTrue(script.contains("__aniliPauseCompetingMedia"))
        assertTrue(script.contains("candidate.addEventListener('play'"))
        assertTrue(script.contains("__aniliVisualOverlapRatio"))
        assertTrue(script.contains("__aniliSetCompetingVideoSuppressed"))
        assertTrue(script.contains("!lockedVerified && bestPlaying"))
        assertTrue(script.contains("lockedScore !== null && lockedVerified"))
        assertTrue(script.contains("window.__aniliContentVideoVerified = true"))
        assertTrue(script.contains("if (!selectionVerified)"))
        assertTrue(script.contains("first.ownerDocument !== second.ownerDocument"))
        assertTrue(script.contains("visibility', 'hidden', 'important"))
    }

    @Test
    fun `only plausible substantially overlapping video is visually suppressed`() {
        val selected = EmbedVideoBounds(0.0, 0.0, 1_000.0, 600.0)
        val mostlyOverlapping = EmbedVideoBounds(100.0, 0.0, 1_000.0, 600.0)
        val separate = EmbedVideoBounds(1_100.0, 0.0, 2_000.0, 600.0)

        assertTrue(
            shouldSuppressCompetingEmbedVideo(
                candidate("duplicate"),
                selected,
                mostlyOverlapping,
            ),
        )
        assertFalse(
            shouldSuppressCompetingEmbedVideo(
                candidate("separate"),
                selected,
                separate,
            ),
        )
        assertFalse(
            shouldSuppressCompetingEmbedVideo(
                candidate("preroll", duration = 30.0),
                selected,
                mostlyOverlapping,
            ),
        )
        assertFalse(
            shouldSuppressCompetingEmbedVideo(
                candidate("iframe-video"),
                selected,
                mostlyOverlapping,
                sameOwnerDocument = false,
            ),
        )
        assertFalse(
            shouldSuppressCompetingEmbedVideo(
                candidate("unverified-competitor"),
                selected,
                mostlyOverlapping,
                selectionVerified = false,
            ),
        )
    }

    @Test
    fun `terminal silence pauses video and audio in nested reachable frames`() {
        val script = silenceEmbedMediaForTeardownJs()

        assertTrue(script.contains("querySelectorAll('video,audio')"))
        assertTrue(script.contains("media[i].pause()"))
        assertTrue(script.contains("media[i].muted = true"))
        assertTrue(script.contains("silenceMedia(child)"))
        assertTrue(script.contains("desiredPlaying: false"))
        assertTrue(
            script.indexOf("supersedePendingPlay();") < script.indexOf("silenceMedia(document)"),
        )
    }

    @Test
    fun `lifecycle pause is reversible and never changes audio settings`() {
        val script = pauseEmbedMediaForLifecycleJs()

        assertTrue(script.contains("querySelectorAll('video,audio')"))
        assertTrue(script.contains("pauseElement(media[i])"))
        assertTrue(script.contains("pauseMedia(child)"))
        assertTrue(script.contains("desiredPlaying: false"))
        assertTrue(
            script.indexOf("supersedePendingPlay();") < script.indexOf("pauseMedia(document)"),
        )
        assertFalse(script.contains("muted"))
        assertFalse(script.contains("volume"))
    }

    @Test
    fun `paused lifecycle installs durable guard for future same-origin media`() {
        val script = pauseEmbedMediaForLifecycleJs()

        assertTrue(script.contains("window.__aniliPlaybackDesiredPlaying = false"))
        assertTrue(script.contains("window.__aniliPlaybackDesiredPlaying !== true"))
        assertTrue(script.contains("root.addEventListener('play'"))
        assertTrue(script.contains("}, true)"))
        assertTrue(script.contains("new Observer(function() { pauseMedia(root); })"))
        assertTrue(script.contains("observer.observe(root.documentElement || root"))
        assertTrue(script.contains("view.setInterval(function() { pauseMedia(root); }, 1000)"))
        assertTrue(script.contains("installPlayGuard(child)"))
        assertTrue(script.indexOf("installPlayGuard(document)") < script.indexOf("pauseMedia(document)"))
    }

    @Test
    fun `paused page setup runs pause-all epoch barrier before authenticated polling`() {
        val pausedSetup = authenticatedProgressSetupJs(
            navigationGeneration = 8L,
            capabilityToken = "capability",
            resumeDesiredPlaying = false,
        )
        val playingSetup = authenticatedProgressSetupJs(
            navigationGeneration = 9L,
            capabilityToken = "capability",
            resumeDesiredPlaying = true,
        )

        val pauseBarrier = pausedSetup.indexOf("function pauseMedia(root)")
        val progressHook = pausedSetup.indexOf("window.__aniliNavigationToken = navigationToken")
        assertTrue(pausedSetup.contains("querySelectorAll('video,audio')"))
        assertTrue(pausedSetup.contains("desiredPlaying: false"))
        assertTrue(pausedSetup.contains("window.__aniliPlaybackDesiredPlaying = false"))
        assertTrue(pausedSetup.contains("var capabilityToken = 'capability'"))
        assertTrue(pauseBarrier >= 0)
        assertTrue(progressHook > pauseBarrier)
        assertTrue(playingSetup.contains("function pauseMedia(root)"))
        assertTrue(playingSetup.contains("window.__aniliPlaybackDesiredPlaying = true"))
        assertTrue(playingSetup.contains("root.addEventListener('play'"))
        assertTrue(playingSetup.contains("window.__aniliNavigationToken = navigationToken"))
    }
}
