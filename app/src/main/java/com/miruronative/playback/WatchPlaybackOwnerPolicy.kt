package com.miruronative.playback

/**
 * Identifies one visible watch-screen generation.
 *
 * Tokens deliberately use identity rather than value equality: a token created by another
 * registry can never acquire ownership merely because its generation number happens to match.
 */
internal class WatchPlaybackOwnerToken internal constructor(val generation: Long)

private class WatchPlaybackHandoffRegistration(
    val owner: WatchPlaybackOwnerToken,
    val action: () -> Unit,
)

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
    private var outgoingHandoff: WatchPlaybackHandoffRegistration? = null

    @Synchronized
    fun activate(onHandoffFailure: (Throwable) -> Unit = {}): WatchPlaybackOwnerToken {
        val previousOwner = activeOwner
        val handoff = outgoingHandoff?.takeIf { it.owner === previousOwner }
        // Consume the callback before invoking application code. A re-entrant or late clear from
        // the outgoing screen can then never remove a callback registered by the next owner.
        outgoingHandoff = null
        val failure = runCatching { handoff?.action?.invoke() }.exceptionOrNull()
        failure?.let { runCatching { onHandoffFailure(it) } }

        // The outgoing owner remains active for its entire flush/stop callback. Only after that
        // synchronous barrier has completed may the incoming screen mutate the shared player.
        val incoming = WatchPlaybackOwnerToken(++nextGeneration)
        activeOwner = incoming
        // Discard anything an outgoing callback tried to register re-entrantly for its old token.
        outgoingHandoff = null
        return incoming
    }

    @Synchronized
    fun isActive(token: WatchPlaybackOwnerToken): Boolean = activeOwner === token

    @Synchronized
    fun runIfActive(token: WatchPlaybackOwnerToken, action: () -> Unit): Boolean {
        if (activeOwner !== token) return false
        action()
        return true
    }

    /** Register the work that must finish before a newer watch destination takes ownership. */
    @Synchronized
    fun registerOutgoingHandoff(token: WatchPlaybackOwnerToken, action: () -> Unit): Boolean {
        if (activeOwner !== token) return false
        outgoingHandoff = WatchPlaybackHandoffRegistration(token, action)
        return true
    }

    /** A stale disposal must not clear the callback installed by the owner that superseded it. */
    @Synchronized
    fun clearOutgoingHandoff(token: WatchPlaybackOwnerToken): Boolean {
        if (activeOwner !== token || outgoingHandoff?.owner !== token) return false
        outgoingHandoff = null
        return true
    }

    /** A stale screen must never release the owner that superseded it. */
    @Synchronized
    fun release(token: WatchPlaybackOwnerToken): Boolean {
        if (activeOwner !== token) return false
        if (outgoingHandoff?.owner === token) outgoingHandoff = null
        activeOwner = null
        return true
    }

    @Synchronized
    fun clear() {
        outgoingHandoff = null
        activeOwner = null
    }
}
