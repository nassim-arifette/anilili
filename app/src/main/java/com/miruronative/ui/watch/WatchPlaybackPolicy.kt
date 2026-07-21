package com.miruronative.ui.watch

/**
 * The playback service survives the watch UI, so any state that cannot present a resolved player
 * must explicitly stop the media it may still own from the previous state.
 */
internal fun shouldStopNativePlaybackForWatchState(
    isSuccess: Boolean,
    hasChosenStream: Boolean,
    usesNativePlayer: Boolean,
    isWebFallback: Boolean,
): Boolean = isWebFallback || !isSuccess || !hasChosenStream || !usesNativePlayer

/**
 * Guards the asynchronous MediaController connection and the later prepare operation for one
 * PlayerSurface lifetime. MediaController futures may finish after Compose has already removed
 * their surface; work accepted after [release] would otherwise be able to start orphan audio.
 */
internal class NativePlaybackSurfaceLease {
    private val lock = Any()
    private var active = true

    fun runIfActive(action: () -> Unit): Boolean = synchronized(lock) {
        if (!active) return@synchronized false
        action()
        true
    }

    fun release() {
        synchronized(lock) { active = false }
    }
}
