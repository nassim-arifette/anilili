package com.miruronative.data.auth

/**
 * Owns the in-memory AniList token and serializes every replacement with expiry cleanup.
 *
 * Expiration is evaluated outside the lock because decoding a token is caller-owned work. The
 * generation check and the destructive cleanup still happen atomically, so cleanup based on an
 * old token can never clear a token installed while that check was running.
 */
internal class AuthTokenSession(initialToken: String? = null) {
    private val gate = SessionGate()
    private var token: String? = initialToken

    fun current(
        isExpired: (String) -> Boolean,
        clearExpired: (String) -> Unit,
    ): String? {
        while (true) {
            val snapshot = gate.snapshot { token }
            val candidate = snapshot.value ?: return null
            if (!isExpired(candidate)) return candidate

            val cleared = gate.invalidateIfCurrent(snapshot) {
                clearExpired(candidate)
                token = null
            }
            if (cleared) return null
            // Another session mutation won while expiry was being checked. Re-read it instead of
            // applying cleanup that belongs to the previous token.
        }
    }

    fun replace(token: String?, commit: () -> Unit) {
        gate.invalidate {
            commit()
            this.token = token
        }
    }
}
