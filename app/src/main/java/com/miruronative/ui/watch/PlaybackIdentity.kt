package com.miruronative.ui.watch

/** Identifies the exact logical playback item that emitted a native-player progress callback. */
data class PlaybackIdentity(
    val animeId: Int,
    val episodeNumber: Double,
    val generation: Int,
    val mediaId: String,
)

/** The native media identities that the currently published watch state is allowed to report. */
internal data class ActivePlaybackTarget(
    val animeId: Int,
    val episodeNumber: Double,
    val generation: Int,
    val mediaIds: Set<String>,
)

/**
 * A callback is current only when both its logical episode and its concrete Media3 item still
 * belong to the state on screen. The media-id check closes the short interval where Compose has
 * published the next episode but the controller is still holding the previous MediaItem.
 */
internal fun acceptsPlaybackProgress(
    callback: PlaybackIdentity,
    active: ActivePlaybackTarget,
): Boolean = callback.animeId == active.animeId &&
    callback.episodeNumber == active.episodeNumber &&
    callback.generation == active.generation &&
    callback.mediaId.isNotBlank() &&
    callback.mediaId in active.mediaIds

/** Media/quality changes share one save throttle only while the logical playback stays the same. */
internal fun isSamePlaybackSession(
    first: PlaybackIdentity,
    second: PlaybackIdentity,
): Boolean = first.animeId == second.animeId &&
    first.episodeNumber == second.episodeNumber &&
    first.generation == second.generation
