package com.miruronative.ui.watch

data class NativePlaybackIdentity(
    val playbackId: String,
    val animeId: Int,
    val mediaId: String,
    val episodeNumber: Double,
)

data class NativePlaybackCompletion(
    val identity: NativePlaybackIdentity,
    val reportedPositionMs: Long,
    val durationMs: Long,
)

internal data class NativeCompletionCommit(
    val identity: NativePlaybackIdentity,
    val positionMs: Long,
    val durationMs: Long,
)

internal fun isCurrentNativePlaybackIdentity(
    identity: NativePlaybackIdentity,
    currentAnimeId: Int,
    currentEpisodeNumber: Double,
    availableMediaIds: Set<String>,
): Boolean =
    identity.playbackId.isNotBlank() &&
        identity.animeId == currentAnimeId &&
        identity.mediaId.isNotBlank() &&
        identity.episodeNumber.isFinite() &&
        identity.episodeNumber == currentEpisodeNumber &&
        identity.mediaId in availableMediaIds

/**
 * Accepts one terminal event for the exact MediaItem instance currently owned by the episode.
 * STATE_ENDED is authoritative, so a valid completion is stored at duration even if the player's
 * final sampled position trails the duration slightly.
 */
internal fun planNativeCompletionCommit(
    completion: NativePlaybackCompletion,
    activeIdentity: NativePlaybackIdentity?,
    currentAnimeId: Int,
    currentEpisodeNumber: Double,
    availableMediaIds: Set<String>,
    alreadyCommitted: Boolean,
): NativeCompletionCommit? {
    if (alreadyCommitted || completion.identity != activeIdentity) return null
    if (
        !isCurrentNativePlaybackIdentity(
            identity = completion.identity,
            currentAnimeId = currentAnimeId,
            currentEpisodeNumber = currentEpisodeNumber,
            availableMediaIds = availableMediaIds,
        )
    ) {
        return null
    }
    if (completion.reportedPositionMs < 0L || completion.durationMs <= 0L) return null
    return NativeCompletionCommit(
        identity = completion.identity,
        positionMs = completion.durationMs,
        durationMs = completion.durationMs,
    )
}

/** Runs autoplay only after the terminal commit has synchronously succeeded. */
internal fun finalizeNativeCompletionThenNavigate(
    completion: NativePlaybackCompletion,
    shouldNavigate: Boolean,
    commit: (NativePlaybackCompletion) -> Boolean,
    navigate: () -> Unit,
): Boolean {
    val committed = commit(completion)
    if (committed && shouldNavigate) navigate()
    return committed
}
