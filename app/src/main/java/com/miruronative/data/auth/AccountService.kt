package com.miruronative.data.auth

/** Which list service the user is signed into. The app allows exactly one at a time. */
enum class AccountService(val label: String) {
    ANILIST("AniList"),
    MAL("MyAnimeList"),
    ;

    companion object {
        /** AniList wins if both somehow hold tokens (pre-MAL installs can only have AniList). */
        val active: AccountService?
            get() = when {
                AuthManager.isLoggedIn -> ANILIST
                MalAuthManager.isLoggedIn -> MAL
                else -> null
            }
    }
}

/** Identity of the credentials that own one profile request. */
internal data class AccountSessionIdentity(
    val service: AccountService,
    val generation: Long,
)

/**
 * Captures both the selected account provider and that provider's current login generation.
 * A same-provider logout/login therefore never looks like the original account.
 */
internal fun currentAccountSession(): AccountSessionIdentity? {
    if (AuthManager.current() != null) {
        AuthManager.sessionGeneration()?.let {
            return AccountSessionIdentity(AccountService.ANILIST, it)
        }
    }
    MalAuthManager.sessionGeneration()?.let {
        return AccountSessionIdentity(AccountService.MAL, it)
    }
    return null
}

/**
 * Runs [change] only while [session] still owns the selected account.
 *
 * The check and callback execute under the authentication gates, so logout or a replacement
 * login cannot land between validation and a profile-store mutation. AniList has selection
 * priority, therefore a MAL commit also proves that no AniList session became active.
 */
internal fun commitIfCurrentAccountSession(
    session: AccountSessionIdentity,
    change: () -> Unit,
): Boolean = when (session.service) {
    AccountService.ANILIST -> AuthManager.commitIfSessionCurrent(session.generation, change)
    AccountService.MAL -> {
        var committed = false
        AuthManager.commitIfLoggedOut {
            committed = MalAuthManager.commitIfSessionCurrent(session.generation, change)
        }
        committed
    }
}

internal fun isCurrentAccountSession(session: AccountSessionIdentity): Boolean =
    commitIfCurrentAccountSession(session) {}
