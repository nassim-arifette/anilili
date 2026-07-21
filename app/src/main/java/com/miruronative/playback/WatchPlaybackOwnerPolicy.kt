package com.miruronative.playback

/**
 * Identifies one visible watch-screen generation.
 *
 * Tokens deliberately use identity rather than value equality: a token created by another
 * registry can never acquire ownership merely because its generation number happens to match.
 */
internal class WatchPlaybackOwnerToken internal constructor(val generation: Long)

/**
 * Serializes access to the process-wide player across overlapping watch screens.
 *
 * Navigation transitions can keep the outgoing destination composed after the incoming one has
 * started. Activating the incoming screen immediately invalidates every operation from the old
 * token, including teardown that arrives late. Running the guarded action under the same lock as
 * activation closes the check-then-act race as well.
 */
internal class WatchPlaybackOwnerRegistry {
    private var nextGeneration = 0L
    private var activeOwner: WatchPlaybackOwnerToken? = null

    @Synchronized
    fun activate(): WatchPlaybackOwnerToken =
        WatchPlaybackOwnerToken(++nextGeneration).also { activeOwner = it }

    @Synchronized
    fun isActive(token: WatchPlaybackOwnerToken): Boolean = activeOwner === token

    @Synchronized
    fun runIfActive(token: WatchPlaybackOwnerToken, action: () -> Unit): Boolean {
        if (activeOwner !== token) return false
        action()
        return true
    }

    /** A stale screen must never release the owner that superseded it. */
    @Synchronized
    fun release(token: WatchPlaybackOwnerToken): Boolean {
        if (activeOwner !== token) return false
        activeOwner = null
        return true
    }

    @Synchronized
    fun clear() {
        activeOwner = null
    }
}
