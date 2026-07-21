package com.miruronative.ui.watch

import kotlinx.coroutines.CompletableDeferred

internal class PlaybackTransitionTicket internal constructor(
    val generation: Int,
    internal val ready: CompletableDeferred<Unit> = CompletableDeferred(),
)

/**
 * Prevents a slow resolver from starting while the outgoing player is still alive.
 *
 * WatchScreen acknowledges a generation only after it has synchronously stopped native playback
 * and removed/stopped the embed surface. Superseding transitions cancel the displaced waiter.
 */
internal class PlaybackTransitionBarrier {
    private val lock = Any()
    private var current: PlaybackTransitionTicket? = null

    fun begin(generation: Int): PlaybackTransitionTicket = synchronized(lock) {
        current?.ready?.cancel()
        PlaybackTransitionTicket(generation).also { current = it }
    }

    fun acknowledge(generation: Int): Boolean = synchronized(lock) {
        val ticket = current?.takeIf { it.generation == generation } ?: return@synchronized false
        ticket.ready.complete(Unit)
    }

    suspend fun await(ticket: PlaybackTransitionTicket) {
        ticket.ready.await()
    }

    fun finish(ticket: PlaybackTransitionTicket) = synchronized(lock) {
        if (current === ticket) current = null
        Unit
    }

    fun cancel() = synchronized(lock) {
        current?.ready?.cancel()
        current = null
    }
}
