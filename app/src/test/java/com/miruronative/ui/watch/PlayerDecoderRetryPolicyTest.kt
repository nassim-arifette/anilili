package com.miruronative.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerDecoderRetryPolicyTest {

    @Test
    fun `same media is retried only once`() {
        val mediaId = "https://cdn.example/episode-1.m3u8"

        assertTrue(shouldRetryDecoderForMedia(lastRetriedMediaId = null, failedMediaId = mediaId))
        assertFalse(shouldRetryDecoderForMedia(lastRetriedMediaId = mediaId, failedMediaId = mediaId))
    }

    @Test
    fun `new media gets its own decoder retry`() {
        assertTrue(
            shouldRetryDecoderForMedia(
                lastRetriedMediaId = "https://cdn.example/episode-1.m3u8",
                failedMediaId = "https://cdn.example/episode-2.m3u8",
            ),
        )
    }

    @Test
    fun `missing media is not retried`() {
        assertFalse(shouldRetryDecoderForMedia(lastRetriedMediaId = null, failedMediaId = null))
        assertFalse(shouldRetryDecoderForMedia(lastRetriedMediaId = null, failedMediaId = ""))
    }
}
