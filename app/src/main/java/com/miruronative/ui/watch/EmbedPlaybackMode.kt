package com.miruronative.ui.watch

/**
 * Defines which app-owned playback features may be attached to an embedded page.
 *
 * A generic website can change the title or episode without telling the app. Such a page must use
 * [UNMANAGED] so its media cannot be attributed to the route that happened to open it.
 */
enum class EmbedPlaybackMode(
    val exposesNativeBridge: Boolean,
    val restoresPosition: Boolean,
    val controlsPlayback: Boolean,
    val automatesEpisode: Boolean,
) {
    MANAGED(
        exposesNativeBridge = true,
        restoresPosition = true,
        controlsPlayback = true,
        automatesEpisode = true,
    ),
    UNMANAGED(
        exposesNativeBridge = false,
        restoresPosition = false,
        controlsPlayback = false,
        automatesEpisode = false,
    ),
}
