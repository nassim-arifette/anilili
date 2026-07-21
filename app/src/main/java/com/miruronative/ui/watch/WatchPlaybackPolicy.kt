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
 * A resolving player is deliberately represented by no surface. The outgoing surface must be
 * gone before [WatchViewModel] is allowed to start resolving a replacement, because some embed
 * resolvers create WebView media of their own.
 */
internal fun playerModeForPlaybackTransition(
    desiredMode: WatchPlayerMode,
    isResolving: Boolean,
    teardownGeneration: Int?,
): WatchPlayerMode = if (isResolving || teardownGeneration != null) {
    WatchPlayerMode.INACTIVE
} else {
    desiredMode
}

/** A teardown may be acknowledged only after Compose has committed the surface-free mode. */
internal fun canAcknowledgePlaybackTeardown(
    teardownGeneration: Int?,
    requestedMode: WatchPlayerMode,
    renderedMode: WatchPlayerMode?,
): Boolean = teardownGeneration != null &&
    requestedMode == WatchPlayerMode.INACTIVE &&
    renderedMode == WatchPlayerMode.INACTIVE

/** Never expose retained Success data after start() until Compose observes its replacement. */
internal fun canAuthorizeStartedRoute(
    previousStateWasSuccess: Boolean,
    replacementStateObserved: Boolean,
): Boolean = !previousStateWasSuccess || replacementStateObserved

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
