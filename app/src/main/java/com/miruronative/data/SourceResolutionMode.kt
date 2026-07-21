package com.miruronative.data

/**
 * Declares whether resolving a source is allowed to start a browser-backed media page.
 *
 * Background validation only needs to prove that a server returns a usable stream. It must not
 * activate helpers such as the hidden Flixcloud WebView while another episode is already playing.
 */
enum class SourceResolutionMode(val allowsHiddenMediaResolver: Boolean) {
    PLAYBACK(allowsHiddenMediaResolver = true),
    BACKGROUND_VALIDATION(allowsHiddenMediaResolver = false),
}

/** Central call-site gate for browser-backed resolvers; the callback is never touched in probes. */
internal suspend fun <T> resolveHiddenMediaIfAllowed(
    mode: SourceResolutionMode,
    resolver: suspend () -> T,
): T? = if (mode.allowsHiddenMediaResolver) resolver() else null
