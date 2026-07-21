package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerDecoderRetryPolicyTest {

    @Test
    fun `lower alternate urls are ordered nearest resolution first`() {
        val session = session(generation = 1)
        val policy = PlayerDecoderRetryPolicy().apply { startSession(session) }

        val action = policy.onDecoderFailure(
            session = session,
            failedMediaId = URL_1080,
            failedHeight = 1080,
            candidates = listOf(
                candidate(URL_480, 480),
                candidate(URL_1080, 1080),
                candidate(URL_720_BACKUP, 720),
                candidate(URL_720, 720),
                candidate("https://cdn.example/episode-1-1440.m3u8", 1440),
                candidate("https://cdn.example/episode-1-auto.m3u8", null),
            ),
            positionMs = 91_234L,
        )

        assertEquals(
            DecoderFallbackAction.SwitchStream(
                mediaId = URL_720_BACKUP,
                height = 720,
                resumePositionMs = 91_234L,
            ),
            action,
        )
    }

    @Test
    fun `attempted urls never loop from B back to A`() {
        val session = session(generation = 2)
        val candidates = listOf(candidate(URL_1080, 1080), candidate(URL_720, 720))
        val policy = PlayerDecoderRetryPolicy().apply { startSession(session) }

        assertEquals(
            DecoderFallbackAction.SwitchStream(URL_720, 720, 12_000L),
            policy.onDecoderFailure(session, URL_1080, 1080, candidates, 12_000L),
        )
        assertEquals(
            DecoderFallbackAction.RetryCurrentStreamCapped(URL_720, 720, 13_000L),
            policy.onDecoderFailure(session, URL_720, 720, candidates, 13_000L),
        )
        assertEquals(
            DecoderFallbackAction.Exhausted(setOf(URL_1080, URL_720), 14_000L),
            policy.onDecoderFailure(session, URL_720, 720, candidates, 14_000L),
        )
    }

    @Test
    fun `no suitable lower url falls back to one capped manifest retry`() {
        val session = session(generation = 3)
        val policy = PlayerDecoderRetryPolicy().apply { startSession(session) }
        val unsuitable = listOf(
            candidate(URL_720, 720),
            candidate(URL_720_BACKUP, 720),
            candidate(URL_1080, 1080),
            candidate("https://cdn.example/episode-1-auto.m3u8", null),
        )

        assertEquals(
            DecoderFallbackAction.RetryCurrentStreamCapped(URL_720, 720, 5_000L),
            policy.onDecoderFailure(session, URL_720, 720, unsuitable, 5_000L),
        )
        assertEquals(
            DecoderFallbackAction.Exhausted(setOf(URL_720), 5_500L),
            policy.onDecoderFailure(session, URL_720, 720, unsuitable, 5_500L),
        )
    }

    @Test
    fun `position is preserved and a new session resets attempts`() {
        val firstSession = session(generation = 4)
        val secondSession = session(generation = 5)
        val candidates = listOf(candidate(URL_1080, 1080), candidate(URL_720, 720))
        val policy = PlayerDecoderRetryPolicy().apply { startSession(firstSession) }

        assertEquals(
            DecoderFallbackAction.SwitchStream(URL_720, 720, 321_000L),
            policy.onDecoderFailure(firstSession, URL_1080, 1080, candidates, 321_000L),
        )

        policy.startSession(secondSession)

        assertEquals(
            DecoderFallbackAction.IgnoreStaleSession(322_000L),
            policy.onDecoderFailure(firstSession, URL_720, 720, candidates, 322_000L),
        )
        assertEquals(
            DecoderFallbackAction.SwitchStream(URL_720, 720, 0L),
            policy.onDecoderFailure(secondSession, URL_1080, 1080, candidates, -1L),
        )
    }

    private fun session(generation: Int) = NativePlaybackSessionKey(
        animeId = 42,
        episodeNumber = 7.0,
        generation = generation,
    )

    private fun candidate(url: String, height: Int?) = DecoderStreamCandidate(url, height)

    private companion object {
        const val URL_1080 = "https://cdn.example/episode-1-1080.m3u8"
        const val URL_720 = "https://cdn.example/episode-1-720.m3u8"
        const val URL_720_BACKUP = "https://backup.example/episode-1-720.m3u8"
        const val URL_480 = "https://cdn.example/episode-1-480.m3u8"
    }
}
