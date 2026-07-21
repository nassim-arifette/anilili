package com.miruronative.data.auth

/**
 * An opaque MAL callback claim. The authorization code, PKCE verifier, and owning session
 * generation are captured together so a callback can never borrow a newer login's verifier.
 */
class MalAuthorizationCode internal constructor(
    internal val code: String,
    internal val verifier: String,
    internal val ownership: SessionGate.Snapshot<*>,
)

/** Owns the pending MAL login and all session generations that may replace it. */
internal class MalAuthSessionGate {
    private data class PendingLogin(
        val state: String,
        val verifier: String,
    )

    private val gate = SessionGate()
    private var pendingLogin: PendingLogin? = null

    fun beginLogin(state: String, verifier: String) {
        gate.invalidate {
            pendingLogin = PendingLogin(state, verifier)
        }
    }

    /**
     * Validates the callback and snapshots its verifier under the same lock used by
     * [beginLogin]. The returned claim therefore belongs wholly to either the old login or the
     * new one; it can never contain a code from one attempt and a verifier from the other.
     */
    fun claimCode(state: String?, code: String?): MalAuthorizationCode? {
        val callbackState = state ?: return null
        val callbackCode = code?.takeIf { it.isNotBlank() } ?: return null
        val ownership = gate.snapshot {
            pendingLogin?.takeIf { it.state == callbackState }
        }
        val login = ownership.value ?: return null
        return MalAuthorizationCode(
            code = callbackCode,
            verifier = login.verifier,
            ownership = ownership,
        )
    }

    fun isCurrent(code: MalAuthorizationCode): Boolean = gate.isCurrent(code.ownership)

    fun replaceLoginIfCurrent(code: MalAuthorizationCode, change: () -> Unit): Boolean =
        gate.replaceIfCurrent(code.ownership) {
            pendingLogin = null
            change()
        }

    fun <T> snapshot(read: () -> T): SessionGate.Snapshot<T> = gate.snapshot(read)

    fun invalidate(change: () -> Unit) {
        gate.invalidate {
            pendingLogin = null
            change()
        }
    }

    fun commitIfCurrent(snapshot: SessionGate.Snapshot<*>, change: () -> Unit): Boolean =
        gate.commitIfCurrent(snapshot, change)

    fun invalidateIfCurrent(snapshot: SessionGate.Snapshot<*>, change: () -> Unit): Boolean =
        gate.invalidateIfCurrent(snapshot) {
            pendingLogin = null
            change()
        }

    fun isCurrent(snapshot: SessionGate.Snapshot<*>): Boolean = gate.isCurrent(snapshot)
}
