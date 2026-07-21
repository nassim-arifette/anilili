package com.miruronative.data.auth

/**
 * An opaque AniList callback claim. The token and the login generation that produced it travel
 * together, so callers cannot accidentally commit a callback after that login was superseded.
 */
class AniListAuthorizationToken internal constructor(
    internal val token: String,
    internal val ownership: SessionGate.Snapshot<*>,
)

/** Owns the AniList token, pending OAuth attempt, and their shared generation. */
internal class AuthTokenSession(initialToken: String? = null) {
    private val gate = SessionGate()
    private var token: String? = initialToken
    private var pendingOAuthState: String? = null

    /**
     * Starts a new login without discarding the currently authenticated account. It does
     * invalidate callbacks and background work belonging to every older generation.
     */
    fun beginLogin(state: String) {
        gate.invalidate {
            pendingOAuthState = state
        }
    }

    /** Validates a callback and binds its token to the matching login generation atomically. */
    fun claimToken(state: String?, token: String?): AniListAuthorizationToken? {
        val callbackState = state ?: return null
        val callbackToken = token?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val ownership = gate.snapshot {
            pendingOAuthState?.takeIf { it == callbackState }
        }
        if (ownership.value == null) return null
        return AniListAuthorizationToken(callbackToken, ownership)
    }

    /**
     * Installs a claimed OAuth token only while its originating login still owns the session.
     * The caller's durable/in-memory write runs under the same lock as the generation check.
     */
    fun replaceLoginIfCurrent(
        authorization: AniListAuthorizationToken,
        commit: (String) -> Unit,
    ): Boolean = gate.replaceIfCurrent(authorization.ownership) {
        pendingOAuthState = null
        token = authorization.token
        commit(authorization.token)
    }

    /**
     * Returns the current non-expired token. Expiration is evaluated outside the lock because JWT
     * decoding is caller-owned work; destructive cleanup remains generation-checked and atomic.
     */
    fun current(
        isExpired: (String) -> Boolean,
        clearExpired: (String) -> Unit,
    ): String? {
        while (true) {
            val snapshot = gate.snapshot { token }
            val candidate = snapshot.value ?: return null
            if (!isExpired(candidate)) return candidate

            val cleared = gate.commitIfCurrent(snapshot) {
                token = null
                clearExpired(candidate)
            }
            if (cleared) return null
            // Another mutation won while expiry was being checked. Re-read it instead of applying
            // cleanup that belongs to the previous token.
        }
    }

    /** Replaces or clears the whole session and invalidates every outstanding callback. */
    fun replace(token: String?, commit: (String?) -> Unit) {
        gate.invalidate {
            pendingOAuthState = null
            this.token = token
            commit(token)
        }
    }

    fun authenticatedGeneration(): Long? {
        val snapshot = gate.snapshot { token }
        return snapshot.generation.takeIf { snapshot.value != null }
    }

    fun commitIfGenerationCurrent(generation: Long, change: () -> Unit): Boolean {
        var committed = false
        gate.commitIfGenerationCurrent(generation) {
            if (token != null) {
                change()
                committed = true
            }
        }
        return committed
    }

    fun commitIfLoggedOut(change: () -> Unit): Boolean {
        val loggedOut = gate.snapshot { token }
        if (loggedOut.value != null) return false
        return gate.commitIfCurrent(loggedOut, change)
    }
}
