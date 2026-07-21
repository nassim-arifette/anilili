package com.miruronative.ui.watch

/** The only playback owner that the watch screen is currently allowed to render. */
internal enum class WatchPlayerMode {
    INACTIVE,
    NATIVE,
    EMBED,
}

internal enum class PlayerModeHandoffAction {
    RENDER,
    STOP_NATIVE_THEN_RENDER,
}

/**
 * A WebView or terminal state is only safe to render after the service player has been stopped.
 *
 * Treat a missing/previously inactive owner conservatively as well: the service outlives the
 * composable, so it may still hold media left by another watch-screen instance. Native playback
 * is the exception because its [PlayerSurface] takes ownership of that service itself.
 */
internal fun playerModeHandoffAction(
    renderedMode: WatchPlayerMode?,
    requestedMode: WatchPlayerMode,
): PlayerModeHandoffAction = when {
    renderedMode == requestedMode -> PlayerModeHandoffAction.RENDER
    requestedMode == WatchPlayerMode.NATIVE -> PlayerModeHandoffAction.RENDER
    else -> PlayerModeHandoffAction.STOP_NATIVE_THEN_RENDER
}
