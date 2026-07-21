package com.miruronative.ui.watch

import kotlinx.coroutines.CancellationException

/** Identifies both the current watch screen session and the playback request inside that session. */
internal data class PlaybackRequestToken(
    val sessionGeneration: Long,
    val requestGeneration: Long,
)

/**
 * Monotonic ownership gate for asynchronous watch work. Catalog merges belong to a session while
 * source resolution and validation belong to one exact request within that session.
 */
internal class PlaybackRequestGate {
    private var sessionGeneration = 0L
    private var requestGeneration = 0L
    private var pendingRequestGeneration: Long? = null

    fun startSession(): PlaybackRequestToken {
        sessionGeneration++
        requestGeneration++
        pendingRequestGeneration = requestGeneration
        return currentRequest()
    }

    fun nextRequest(): PlaybackRequestToken {
        requestGeneration++
        pendingRequestGeneration = requestGeneration
        return currentRequest()
    }

    fun currentRequest(): PlaybackRequestToken = PlaybackRequestToken(
        sessionGeneration = sessionGeneration,
        requestGeneration = requestGeneration,
    )

    fun isCurrentSession(token: PlaybackRequestToken): Boolean =
        token.sessionGeneration == sessionGeneration

    fun isCurrentRequest(token: PlaybackRequestToken): Boolean =
        isCurrentSession(token) && token.requestGeneration == requestGeneration

    fun hasPendingRequest(): Boolean = pendingRequestGeneration == requestGeneration

    fun finishRequest(token: PlaybackRequestToken): Boolean {
        if (!isCurrentRequest(token)) return false
        pendingRequestGeneration = null
        return true
    }

    fun requireCurrentSession(token: PlaybackRequestToken) {
        if (!isCurrentSession(token)) {
            throw CancellationException("Stale watch session ${token.sessionGeneration}")
        }
    }

    fun requireCurrentRequest(token: PlaybackRequestToken) {
        if (!isCurrentRequest(token)) {
            throw CancellationException(
                "Stale playback request ${token.sessionGeneration}/${token.requestGeneration}",
            )
        }
    }
}
