package com.miruronative.ui.watch

/**
 * Whether AniLili+ can safely expose controls for the currently selected embed video.
 *
 * A page can announce that a video exists before its first duration-bearing tick identifies the
 * selected media. Commands dispatched in that window cannot be matched to their acknowledgement,
 * so bridge availability alone is deliberately insufficient here.
 */
internal fun canUseManagedEmbedControls(
    managedControlsDeclared: Boolean,
    bridgePlaybackAvailable: Boolean,
    activeMediaIdentity: EmbedMediaIdentity?,
    reportedMediaIdentity: EmbedVideoIdentity?,
): Boolean {
    if (!managedControlsDeclared || !bridgePlaybackAvailable) return false
    val active = activeMediaIdentity?.takeIf { it.isConcrete } ?: return false
    return active.videoIdentity == reportedMediaIdentity
}
