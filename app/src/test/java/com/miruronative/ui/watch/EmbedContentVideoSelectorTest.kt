package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
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

        assertTrue(script.contains("querySelectorAll('video')"))
        assertTrue(script.contains("__aniliLooksLikeAd"))
        assertTrue(script.contains("duration < 120.0"))
        assertTrue(script.contains("window.__aniliContentVideo"))
    }
}
