package com.miruronative.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDecoderRetryPolicyTest {

    @Test
    fun `same media item is retried only once`() {
        val policy = PlayerDecoderRetryPolicy()
        val mediaId = "https://cdn.example/episode-1.m3u8"

        policy.onMediaItemSet()

        assertTrue(policy.tryConsumeRetry(mediaId))
        assertFalse(policy.tryConsumeRetry(mediaId))
    }

    @Test
    fun `same url reused by a new media item gets a fresh retry`() {
        val policy = PlayerDecoderRetryPolicy()
        val sharedMediaId = "https://cdn.example/shared-manifest.m3u8"

        policy.onMediaItemSet()
        assertTrue(policy.tryConsumeRetry(sharedMediaId))
        assertFalse(policy.tryConsumeRetry(sharedMediaId))

        policy.onMediaItemSet()
        assertTrue(policy.tryConsumeRetry(sharedMediaId))
        assertFalse(policy.tryConsumeRetry(sharedMediaId))
    }

    @Test
    fun `different media in the current playback gets its own decoder retry`() {
        val policy = PlayerDecoderRetryPolicy()

        policy.onMediaItemSet()
        assertTrue(policy.tryConsumeRetry("https://cdn.example/episode-1.m3u8"))
        assertTrue(policy.tryConsumeRetry("https://cdn.example/episode-1-backup.m3u8"))
    }

    @Test
    fun `missing media is not retried`() {
        val policy = PlayerDecoderRetryPolicy()

        policy.onMediaItemSet()
        assertFalse(policy.tryConsumeRetry(null))
        assertFalse(policy.tryConsumeRetry(""))
        assertFalse(policy.tryConsumeRetry("   "))
    }
}
