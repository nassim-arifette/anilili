package com.miruronative.ui.watch

/** Identifies the exact logical playback item that emitted a native-player progress callback. */
data class PlaybackIdentity(
    val animeId: Int,
    val episodeNumber: Double,
    val generation: Int,
    val mediaId: String,
    /** Optional opaque instance scope when one media id can be replaced inside the same player. */
    val mediaInstanceId: String? = null,
)

/** Last position reported by a concrete, identity-bearing playback item. */
internal data class PlaybackProgressSnapshot(
    val identity: PlaybackIdentity,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * Compose state that belongs to one logical native playback must not use the media URL as its
 * identity. Some providers reuse one manifest URL for several episodes, and quality changes can
 * use several URLs for the same episode.
 */
internal data class NativePlaybackSessionKey(
    val animeId: Int,
    val episodeNumber: Double,
    val generation: Int,
)

internal fun PlaybackIdentity.nativePlaybackSessionKey(): NativePlaybackSessionKey =
    NativePlaybackSessionKey(animeId, episodeNumber, generation)

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

/** A native failure can affect source selection only for the exact current MediaItem. */
internal fun acceptsNativePlaybackError(
    callback: PlaybackIdentity,
    reportedMediaId: String,
    active: ActivePlaybackTarget,
): Boolean = callback.mediaId == reportedMediaId && acceptsPlaybackProgress(callback, active)

/** Media/quality changes share one save throttle only while the logical playback stays the same. */
internal fun isSamePlaybackSession(
    first: PlaybackIdentity,
    second: PlaybackIdentity,
): Boolean = first.animeId == second.animeId &&
    first.episodeNumber == second.episodeNumber &&
    first.generation == second.generation

/** History changes only after the first confirmed playing callback for a logical session. */
internal fun isNewConfirmedPlayback(
    previouslyConfirmed: PlaybackIdentity?,
    callback: PlaybackIdentity,
): Boolean = previouslyConfirmed == null || !isSamePlaybackSession(previouslyConfirmed, callback)

/**
 * Banks the last confirmed, still-current native position before a caller invalidates that
 * playback session. Keeping the write and transition in one helper makes their ordering explicit:
 * a replacement MediaItem may be installed immediately after [transition] starts, at which point
 * callbacks from the old item must be rejected rather than relabelled as the new episode.
 *
 * [persist] is intentionally never called for an unconfirmed sample. In particular, a source that
 * fails during preparation must not create a Continue Watching entry merely because the user
 * selected it.
 */
internal fun <T> flushProgressBeforeTransition(
    candidate: PlaybackProgressSnapshot?,
    confirmedIdentity: PlaybackIdentity?,
    activeTarget: ActivePlaybackTarget?,
    persist: (PlaybackProgressSnapshot) -> Unit,
    transition: () -> T,
): T {
    val current = candidate?.takeIf { progress ->
        progress.positionMs > 0L &&
            activeTarget != null &&
            acceptsPlaybackProgress(progress.identity, activeTarget) &&
            confirmedIdentity != null &&
            isSamePlaybackSession(confirmedIdentity, progress.identity)
    }
    current?.let(persist)
    return transition()
}
