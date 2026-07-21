package com.miruronative.ui.profile

import com.miruronative.data.auth.AccountSessionIdentity

/**
 * Gives each profile load exclusive publication ownership.
 *
 * Coroutine cancellation saves work, while this gate is the correctness boundary: a cancelled
 * request can still return from non-cooperative I/O, so it must still match the latest load
 * generation before it may mutate user-visible state. The caller additionally performs this
 * commit from inside the authentication session gate.
 */
internal class ProfileLoadGate {
    internal data class Request(
        val generation: Long,
        val session: AccountSessionIdentity,
    )

    private val lock = Any()
    private var generation = 0L

    fun begin(session: AccountSessionIdentity): Request = synchronized(lock) {
        Request(++generation, session)
    }

    fun invalidate() {
        synchronized(lock) { generation++ }
    }

    fun isCurrent(request: Request): Boolean = synchronized(lock) {
        request.generation == generation
    }

    fun commitIfCurrent(
        request: Request,
        change: () -> Unit,
    ): Boolean = synchronized(lock) {
        if (request.generation != generation) {
            false
        } else {
            change()
            true
        }
    }
}
