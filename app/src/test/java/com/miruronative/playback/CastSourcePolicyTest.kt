package com.miruronative.playback

import com.miruronative.data.model.StreamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastSourcePolicyTest {
    @Test
    fun `public active source is sent to Cast unchanged`() {
        val active = stream("https://cdn.example/video.m3u8", height = 1080)

        val decision = chooseCastSource(active, listOf(active)) as CastSourceDecision.Ready

        assertEquals(active, decision.stream)
        assertFalse(decision.switchesSource)
    }

    @Test
    fun `header protected source selects closest public alternate`() {
        val active = stream(
            "https://protected.example/1080.m3u8",
            height = 1080,
            referer = "https://provider.example/",
        )
        val public720 = stream("https://public.example/720.m3u8", height = 720)
        val public480 = stream("https://public.example/480.m3u8", height = 480)

        val decision = chooseCastSource(active, listOf(active, public480, public720)) as CastSourceDecision.Ready

        assertEquals(public720, decision.stream)
        assertTrue(decision.switchesSource)
    }

    @Test
    fun `local playlist decryption blocks stock receiver when no alternate exists`() {
        val active = stream(
            "https://protected.example/master.m3u8",
            playlistKey = "secret",
        )

        val decision = chooseCastSource(active, listOf(active)) as CastSourceDecision.Blocked

        assertEquals(CastBlockReason.PLAYLIST_DECRYPTION, decision.reason)
    }

    @Test
    fun `referer requirement blocks stock receiver when no alternate exists`() {
        val active = stream(
            "https://protected.example/video.m3u8",
            referer = "https://provider.example/",
        )

        val decision = chooseCastSource(active, listOf(active)) as CastSourceDecision.Blocked

        assertEquals(CastBlockReason.REQUEST_HEADERS, decision.reason)
    }

    @Test
    fun `embed and non-http URLs are never cast sources`() {
        val active = stream("file:///private/video.m3u8")
        val embed = stream("https://embed.example/watch", type = "embed")

        val decision = chooseCastSource(active, listOf(active, embed)) as CastSourceDecision.Blocked

        assertEquals(CastBlockReason.UNSUPPORTED_URL, decision.reason)
    }

    private fun stream(
        url: String,
        type: String = "hls",
        height: Int? = null,
        referer: String? = null,
        playlistKey: String? = null,
    ) = StreamItem(
        url = url,
        type = type,
        quality = height?.let { "${it}p" },
        audio = "sub",
        referer = referer,
        isActive = true,
        width = null,
        height = height,
        playlistKey = playlistKey,
    )
}
