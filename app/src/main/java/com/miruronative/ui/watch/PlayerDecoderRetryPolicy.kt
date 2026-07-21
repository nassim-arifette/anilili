package com.miruronative.ui.watch

/** Grants at most one decoder fallback retry to each concrete MediaItem installation. */
internal class PlayerDecoderRetryPolicy {
    private var playbackGeneration = 0L
    private var consumedAttempt: RetryAttempt? = null

    fun onMediaItemSet() {
        playbackGeneration++
    }

    fun tryConsumeRetry(failedMediaId: String?): Boolean {
        val mediaId = failedMediaId?.takeIf(String::isNotBlank) ?: return false
        val attempt = RetryAttempt(playbackGeneration, mediaId)
        if (attempt == consumedAttempt) return false
        consumedAttempt = attempt
        return true
    }

    private data class RetryAttempt(
        val playbackGeneration: Long,
        val mediaId: String,
    )
}
