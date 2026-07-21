package com.miruronative.playback

/** The two playback destinations involved in a Cast transfer. */
internal enum class PlaybackRoute {
    LOCAL,
    REMOTE,
}

/** How Cast state should be applied to the player that is about to become active. */
internal enum class CastTransferDirective {
    PRESERVE_PLAY_STATE,
    TRANSFER_LOCAL_PAUSED,
}

/**
 * A Cast disconnect normally mirrors the remote play state onto ExoPlayer. That is useful while
 * the player UI is alive, but would restart local audio after the user has left the watch screen.
 */
internal fun castTransferDirective(
    sourceRoute: PlaybackRoute,
    targetRoute: PlaybackRoute,
    hasLocalPlaybackOwner: Boolean,
): CastTransferDirective =
    if (
        sourceRoute == PlaybackRoute.REMOTE &&
        targetRoute == PlaybackRoute.LOCAL &&
        !hasLocalPlaybackOwner
    ) {
        CastTransferDirective.TRANSFER_LOCAL_PAUSED
    } else {
        CastTransferDirective.PRESERVE_PLAY_STATE
    }

/** A paused receiver session must outlive removal of the sender app's task. */
internal fun shouldStopPlaybackServiceAfterTaskRemoved(
    playerInitialized: Boolean,
    route: PlaybackRoute,
    mediaItemCount: Int,
    playWhenReady: Boolean,
): Boolean {
    if (!playerInitialized) return true
    if (route == PlaybackRoute.REMOTE && mediaItemCount > 0) return false
    return !playWhenReady || mediaItemCount == 0
}

internal data class EpisodeNavigatorPlaybackIdentity(
    val animeId: Int,
    val episodeNumber: Double,
    val playbackGeneration: Int,
    val playbackId: String? = null,
)

/** The playback UUID may be bound after Compose registers the logical episode callback. */
internal fun EpisodeNavigatorPlaybackIdentity.matchesLogicalPlayback(
    other: EpisodeNavigatorPlaybackIdentity,
): Boolean =
    animeId == other.animeId &&
        episodeNumber == other.episodeNumber &&
        playbackGeneration == other.playbackGeneration

internal fun acceptsEpisodeNavigatorPlayback(
    activeOwnerGeneration: Long,
    active: EpisodeNavigatorPlaybackIdentity,
    expected: RemotePlaybackHistoryIdentity?,
): Boolean = expected == null || (
    activeOwnerGeneration == expected.watchOwnerGeneration &&
        active.animeId == expected.animeId &&
        active.episodeNumber == expected.episodeNumber &&
        active.playbackGeneration == expected.generation &&
        active.playbackId == expected.playbackId
    )

internal class LocalPlaybackOwnerToken(val id: Long)

/**
 * Tracks every live native player surface. Tokens make disposal idempotent and keep overlapping
 * Compose surfaces from clearing each other's ownership during navigation or recomposition.
 */
internal class LocalPlaybackOwnerRegistry {
    private var nextId = 0L
    private val owners = mutableSetOf<LocalPlaybackOwnerToken>()

    @Synchronized
    fun acquire(): LocalPlaybackOwnerToken =
        LocalPlaybackOwnerToken(++nextId).also(owners::add)

    @Synchronized
    fun release(token: LocalPlaybackOwnerToken): Boolean = owners.remove(token)

    @Synchronized
    fun hasOwner(): Boolean = owners.isNotEmpty()

    @Synchronized
    fun isLatest(token: LocalPlaybackOwnerToken): Boolean =
        owners.maxByOrNull(LocalPlaybackOwnerToken::id) === token
}
