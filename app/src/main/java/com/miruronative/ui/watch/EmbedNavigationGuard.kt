package com.miruronative.ui.watch

import java.net.URI

/** Stable playback identity supplied by WatchContent independently of provider URL reuse. */
data class EmbedPlaybackKey(
    val animeId: Int,
    val provider: String,
    val category: String,
    val episodeNumber: String,
    val sourceGeneration: Int = 0,
)

/** Compose remember key for one logical playback plus its selected embed document. */
internal data class EmbedNavigationIdentity(
    val playbackKey: EmbedPlaybackKey,
    val streamUrl: String,
    val referer: String?,
    val usesIframeShell: Boolean,
)

internal data class EmbedNavigationRequest(
    val streamUrl: String,
    val documentUrl: String,
    val allowedMainFrameHost: String?,
    val resumePositionMs: Long,
)

/**
 * Capability for one explicit WebView load. A session is never reused, even when AndroidView keeps
 * the same WebView instance alive across stream and episode changes.
 */
internal class EmbedNavigationSession internal constructor(
    val generation: Long,
    val request: EmbedNavigationRequest,
) {
    internal var mainFrameStarted: Boolean = false
    internal var mainFrameFinished: Boolean = false
    internal var currentMainFrameUrl: String? = null

    val bridgeToken: String
        get() = generation.toString()
}

/**
 * Rejects work emitted by a document after a newer explicit navigation has superseded it.
 *
 * WebViewClient callbacks do not expose an application navigation id. Each navigation therefore
 * gets both its own client/session and URL-phase checks, while the injected JavaScript carries the
 * generation explicitly. Cross-origin iframe access is intentionally outside this contract.
 */
internal class EmbedNavigationGuard {
    private var nextGeneration: Long = 0L

    @Volatile
    private var activeGeneration: Long = NO_GENERATION

    @Synchronized
    fun begin(request: EmbedNavigationRequest): EmbedNavigationSession {
        val generation = ++nextGeneration
        activeGeneration = generation
        return EmbedNavigationSession(generation, request)
    }

    /** Invalidates callbacks immediately, before the replacement document starts loading. */
    @Synchronized
    fun invalidate(session: EmbedNavigationSession): Boolean {
        if (!isCurrent(session)) return false
        activeGeneration = NO_GENERATION
        return true
    }

    fun isCurrent(session: EmbedNavigationSession): Boolean =
        activeGeneration == session.generation

    fun acceptsBridgeToken(token: String): Boolean =
        activeGeneration != NO_GENERATION && token == activeGeneration.toString()

    @Synchronized
    fun acceptPageStarted(
        session: EmbedNavigationSession,
        callbackUrl: String?,
        visibleUrl: String?,
    ): Boolean {
        if (!isCurrent(session)) return false
        val actualUrl = callbackUrl ?: return false
        if (!session.mainFrameStarted) {
            if (!sameDocumentUrl(actualUrl, session.request.documentUrl)) return false
            session.mainFrameStarted = true
        } else if (
            session.mainFrameFinished ||
            !session.allowsDocumentUrl(actualUrl) ||
            (visibleUrl != null && !sameDocumentUrl(actualUrl, visibleUrl))
        ) {
            return false
        }
        session.currentMainFrameUrl = actualUrl
        return true
    }

    @Synchronized
    fun acceptPageFinished(
        session: EmbedNavigationSession,
        callbackUrl: String?,
        visibleUrl: String?,
    ): Boolean {
        if (
            !isCurrent(session) ||
            !session.mainFrameStarted ||
            session.mainFrameFinished ||
            !sameDocumentUrl(callbackUrl, visibleUrl)
        ) {
            return false
        }
        val actualUrl = callbackUrl ?: return false
        if (!sameDocumentUrl(actualUrl, session.currentMainFrameUrl)) return false
        session.mainFrameFinished = true
        session.currentMainFrameUrl = actualUrl
        return true
    }

    @Synchronized
    fun acceptMainFrameError(
        session: EmbedNavigationSession,
        requestUrl: String?,
        visibleUrl: String?,
    ): Boolean {
        if (!isCurrent(session) || session.mainFrameFinished || requestUrl == null) return false
        if (!session.mainFrameStarted) {
            return sameDocumentUrl(requestUrl, session.request.documentUrl)
        }
        val knownMainFrame = sameDocumentUrl(requestUrl, session.currentMainFrameUrl) ||
            sameDocumentUrl(requestUrl, session.request.documentUrl)
        val visibleMatches = visibleUrl == null || sameDocumentUrl(requestUrl, visibleUrl)
        return knownMainFrame && visibleMatches
    }

    @Synchronized
    fun allowsMainFrameNavigation(session: EmbedNavigationSession, targetUrl: String?): Boolean {
        if (
            !isCurrent(session) ||
            !session.mainFrameStarted ||
            session.mainFrameFinished ||
            targetUrl == null ||
            !session.allowsDocumentUrl(targetUrl)
        ) {
            return false
        }
        // shouldOverrideUrlLoading identifies a redirect as belonging to this navigation before
        // its eventual page-finished/error callback arrives.
        session.currentMainFrameUrl = targetUrl
        return true
    }

    private fun EmbedNavigationSession.allowsDocumentUrl(url: String): Boolean {
        if (sameDocumentUrl(url, request.documentUrl)) return true
        val host = normalizedHost(url) ?: return false
        val allowedHosts = listOfNotNull(
            request.allowedMainFrameHost?.lowercase()?.removePrefix("www."),
            normalizedHost(request.documentUrl),
        )
        return allowedHosts.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    private companion object {
        const val NO_GENERATION = 0L
    }
}

internal const val REVOKE_EMBED_NAVIGATION_JS =
    "window.__aniliNavigationRevoked = true;"

internal fun progressPollJs(navigationGeneration: Long): String = """
    (function() {
      var navigationToken = '$navigationGeneration';
      if (window.__aniliNavigationToken && window.__aniliNavigationToken !== navigationToken) return;
      window.__aniliNavigationToken = navigationToken;
      window.__aniliNavigationRevoked = false;
      if (window.__aniliProgressHookedFor === navigationToken) return;
      window.__aniliProgressHookedFor = navigationToken;
      function findVideo() {
        var v = document.querySelector('video');
        if (v) return v;
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try {
            var d = frames[i].contentDocument;
            if (d) {
              var fv = d.querySelector('video');
              if (fv) return fv;
            }
          } catch (e) { /* cross-origin */ }
        }
        return null;
      }
      var timer = setInterval(function() {
        if (window.__aniliNavigationRevoked || window.__aniliNavigationToken !== navigationToken) {
          clearInterval(timer);
          return;
        }
        try {
          var v = findVideo();
          if (v && !window.__aniliVideoReported) {
            window.__aniliVideoReported = true;
            AniliProgress.onVideoAvailable(navigationToken);
          }
          if (v && isFinite(v.duration) && v.duration > 0 && v.currentTime >= 0) {
            AniliProgress.onTick(
              navigationToken,
              v.currentTime,
              v.duration,
              !v.paused,
              v.muted,
              v.volume
            );
          }
        } catch (e) { /* bridge detached */ }
      }, 1000);
    })();
""".trimIndent()

internal fun embedNavigationJsGuard(navigationGeneration: Long): String =
    "if (window.__aniliNavigationToken !== '$navigationGeneration' || " +
        "window.__aniliNavigationRevoked === true) return false;"

private fun sameDocumentUrl(first: String?, second: String?): Boolean {
    val firstNormalized = normalizedDocumentUrl(first) ?: return false
    return firstNormalized == normalizedDocumentUrl(second)
}

private fun normalizedDocumentUrl(value: String?): String? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return runCatching {
        val uri = URI(raw)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("www.")
        if (scheme == null || host == null) return@runCatching raw.substringBefore('#')
        val port = when {
            uri.port < 0 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }
        val path = uri.rawPath?.takeIf(String::isNotEmpty) ?: "/"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        "$scheme://$host$port$path$query"
    }.getOrElse { raw.substringBefore('#') }
}

private fun normalizedHost(value: String?): String? = runCatching {
    URI(value?.trim().orEmpty()).host?.lowercase()?.removePrefix("www.")
}.getOrNull()
