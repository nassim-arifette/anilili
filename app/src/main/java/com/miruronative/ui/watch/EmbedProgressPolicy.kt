package com.miruronative.ui.watch

internal data class EmbedProgressSample(
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * Keeps WebView progress usable without letting missing metadata overwrite the last valid sample.
 * Finalization is edge-triggered so lifecycle pause -> stop -> release only persists once unless
 * the player reports a newer position in between.
 */
internal class EmbedProgressFinalizationPolicy {
    private var latest: EmbedProgressSample? = null
    private var lastFinalized: EmbedProgressSample? = null

    fun observe(positionMs: Long, durationMs: Long): EmbedProgressSample? =
        normalizeEmbedProgress(positionMs, durationMs)?.also { latest = it }

    fun takePendingFinalization(): EmbedProgressSample? {
        val sample = latest ?: return null
        if (sample == lastFinalized) return null
        lastFinalized = sample
        return sample
    }
}

internal class WebProgressCallbackGate {
    @Volatile
    var isOpen: Boolean = true
        private set

    @Synchronized
    fun close(): Boolean {
        if (!isOpen) return false
        isOpen = false
        return true
    }
}

internal fun normalizeEmbedProgress(positionMs: Long, durationMs: Long): EmbedProgressSample? {
    if (positionMs < 0L || durationMs <= 0L) return null
    return EmbedProgressSample(
        positionMs = positionMs.coerceAtMost(durationMs),
        durationMs = durationMs,
    )
}

internal fun embedProgressFromSeconds(positionSec: Double, durationSec: Double): EmbedProgressSample? {
    if (!positionSec.isFinite() || !durationSec.isFinite()) return null
    // A provider-side zero is also the placeholder emitted before resume seeking has completed.
    // Explicit app seeks to zero use [normalizeEmbedProgress] directly and remain valid.
    if (positionSec <= 0.0 || durationSec <= 0.0) return null
    val maxSeconds = Long.MAX_VALUE / 1_000.0
    if (positionSec > maxSeconds || durationSec > maxSeconds) return null
    val positionMs = (positionSec * 1_000.0).toLong()
    val durationMs = (durationSec * 1_000.0).toLong()
    if (positionMs <= 0L || durationMs <= 0L) return null
    return normalizeEmbedProgress(
        positionMs = positionMs,
        durationMs = durationMs,
    )
}
