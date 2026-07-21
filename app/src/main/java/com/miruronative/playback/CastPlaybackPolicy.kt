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
}
