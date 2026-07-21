package com.miruronative.data.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.miruronative.data.reminder.AniListNotificationPushManager
import com.miruronative.data.reminder.ReleaseSyncScheduler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull

/**
 * AniList OAuth (Implicit Grant). AniList redirects to `http://localhost/#access_token=…`; the
 * login WebView intercepts that URL before Android networking tries to open localhost. No client
 * secret is stored in the app. Tokens are long-lived (~1 year; AniList has no refresh).
 */
object AuthManager {
    const val CLIENT_ID = "45552"
    const val REDIRECT = "http://localhost"
    private const val AUTHORIZE_ENDPOINT = "https://anilist.co/api/v2/oauth/authorize"

    private lateinit var tokenStore: SecureTokenStore
    private lateinit var appContext: Context
    private val tokenSession = AuthTokenSession()

    private val _token = MutableStateFlow<String?>(null)
    val token = _token.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        tokenStore = SecureTokenStore(appContext)
        val restoredToken = tokenStore.load()?.takeUnless(::isJwtExpired)
        tokenSession.replace(restoredToken) { token ->
            _token.value = token
            if (token == null) tokenStore.clear()
        }
    }

    fun current(): String? = tokenSession.current(
        isExpired = ::isJwtExpired,
        clearExpired = {
            tokenStore.clear()
            _token.value = null
            AniListNotificationPushManager.clearDelivered(appContext)
        },
    )

    val isLoggedIn: Boolean get() = current() != null
    fun viewerId(): Int? = current()?.let(::jwtSubject)

    fun authorizeUrl(): String {
        val state = ByteArray(24).also(SecureRandom()::nextBytes)
            .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING) }
        tokenSession.beginLogin(state)
        // AniList's implicit flow uses the redirect registered for the client. Supplying the same
        // redirect explicitly currently makes its post-login grant step fail with unsupported_grant_type.
        return Uri.parse(AUTHORIZE_ENDPOINT).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("state", state)
            .build()
            .toString()
    }

    /** Persists a callback only if no newer login/session mutation superseded it. */
    fun setToken(authorization: AniListAuthorizationToken): Boolean {
        val committed = tokenSession.replaceLoginIfCurrent(authorization) { token ->
            tokenStore.save(token)
            _token.value = token
        }
        if (!committed) return false
        AniListNotificationPushManager.clearDelivered(appContext)
        ReleaseSyncScheduler.runNow(appContext)
        return true
    }

    fun logout() {
        clearToken(scheduleSync = true)
    }

    /** True once a redirect URL carries the token; extract it with [extractToken]. */
    fun isRedirect(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        return uri.scheme == "http" && uri.host == "localhost" && uri.fragment?.contains("access_token=") == true
    }

    fun extractToken(url: String): AniListAuthorizationToken? {
        if (!isRedirect(url)) return null
        val uri = Uri.parse(url)
        val fragment = Uri.parse("$REDIRECT/?${uri.fragment.orEmpty()}")
        return tokenSession.claimToken(
            state = fragment.getQueryParameter("state"),
            token = fragment.getQueryParameter("access_token"),
        )
    }

    private fun clearToken(scheduleSync: Boolean) {
        tokenSession.replace(null) {
            tokenStore.clear()
            _token.value = null
        }
        AniListNotificationPushManager.clearDelivered(appContext)
        if (scheduleSync) ReleaseSyncScheduler.runNow(appContext)
    }

    /** Auth generation used to reject UI results loaded for a replaced account. */
    internal fun sessionGeneration(): Long? = tokenSession.authenticatedGeneration()

    /** A captured token for one generation; it can never silently become another account's. */
    internal fun tokenForSession(generation: Long): String? {
        current() ?: return null
        return tokenSession.tokenForGeneration(generation)
    }

    /** Compares the generation and publishes [change] under the auth-session lock. */
    internal fun commitIfSessionCurrent(generation: Long, change: () -> Unit): Boolean =
        tokenSession.commitIfGenerationCurrent(generation, change)

    /** Publishes [change] atomically only while AniList remains logged out. */
    internal fun commitIfLoggedOut(change: () -> Unit): Boolean =
        tokenSession.commitIfLoggedOut(change)
}

private val jwtJson = Json { ignoreUnknownKeys = true }

internal fun isJwtExpired(token: String, nowEpochSeconds: Long = System.currentTimeMillis() / 1_000): Boolean {
    val expiration = jwtLongClaim(token, "exp") ?: return false
    return expiration <= nowEpochSeconds + TOKEN_EXPIRY_SKEW_SECONDS
}

internal fun jwtSubject(token: String): Int? = jwtLongClaim(token, "sub")?.toInt()

private fun jwtLongClaim(token: String, name: String): Long? = runCatching {
    val payload = token.split('.').getOrNull(1) ?: return@runCatching null
    val decoded = java.util.Base64.getUrlDecoder().decode(payload)
    val value = jwtJson.parseToJsonElement(decoded.toString(Charsets.UTF_8)).jsonObject[name]?.jsonPrimitive
    value?.longOrNull ?: value?.intOrNull?.toLong()
}.getOrNull()

private const val TOKEN_EXPIRY_SKEW_SECONDS = 60L
