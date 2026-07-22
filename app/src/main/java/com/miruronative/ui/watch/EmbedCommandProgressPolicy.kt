package com.miruronative.ui.watch

internal data class ConfirmedEmbedCommandProgress(
    val identity: EmbedMediaIdentity,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * Turns an acknowledged seek into a progress sample only after this exact concrete video has
 * already produced real playing progress. This preserves a paused seek without letting a user
 * merely scrubbing an unplayed embed manufacture Continue Watching history.
 */
internal fun confirmedProgressAfterEmbedSeek(
    resolution: EmbedCommandResolution,
    activeMediaIdentity: EmbedMediaIdentity?,
    currentPlaybackKey: EmbedPlaybackKey,
    confirmedMediaInstanceId: String?,
    durationMs: Long,
): ConfirmedEmbedCommandProgress? {
    val confirmed = resolution as? EmbedCommandResolution.Confirmed ?: return null
    if (confirmed.command.kind != EmbedCommandKind.SEEK || !confirmed.acknowledgement.succeeded) {
        return null
    }
    val active = activeMediaIdentity?.takeIf { identity ->
        identity.isConcrete &&
            identity.playbackKey == currentPlaybackKey &&
            identity.navigationGeneration == confirmed.command.navigationGeneration &&
            identity.videoIdentity == confirmed.command.mediaIdentity &&
            identity.videoIdentity == confirmed.acknowledgement.mediaIdentity &&
            identity.mediaInstanceId == confirmedMediaInstanceId
    } ?: return null
    val positionMs = confirmed.acknowledgement.positionMs
    if (positionMs <= 0L || durationMs <= 0L) return null
    return ConfirmedEmbedCommandProgress(active, positionMs, durationMs)
}
