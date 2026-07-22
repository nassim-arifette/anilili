package com.miruronative.data.remote

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.diagnostics.privacySafeUrlDiagnosticLabel
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Pure URL policy kept outside [FlixcloudBridge] so local JVM tests do not initialize WebView. */
internal fun flixcloudCaptureUrl(embedUrl: String, timestampMs: Long = System.currentTimeMillis()): String {
    val url = embedUrl.toHttpUrlOrNull() ?: return embedUrl
    val builder = url.newBuilder()
    url.queryParameterNames
        .filter { it.equals("autoplay", ignoreCase = true) }
        .forEach { name -> builder.removeAllQueryParameters(name) }
    builder.addQueryParameter("autoPlay", "false")
    if (url.queryParameter("skI") == null) builder.addQueryParameter("skI", "false")
    if (url.queryParameter("skO") == null) builder.addQueryParameter("skO", "false")
    if (url.queryParameter("kuudere_ts") == null) {
        builder.addQueryParameter("kuudere_ts", timestampMs.toString())
    }
    return builder.build().toString()
}

/** Only requests made by a trusted Flixcloud execution origin may use the native fallback. */
internal fun isTrustedFlixcloudRequest(headers: Map<String, String>): Boolean {
    val origin = headers.entries
        .singleOrNull { (name, _) -> name.equals("origin", true) }
        ?.value
        ?: return false
    val uri = runCatching { URI(origin) }.getOrNull() ?: return false
    val host = uri.host.orEmpty().lowercase()
    val trustedPort = uri.port == -1 || uri.port == 443
    return uri.scheme.equals("https", true) && trustedPort &&
        (host == "flixcloud.cc" || host.endsWith(".flixcloud.cc"))
}

/**
 * Hidden flixcloud resolver. The embed owns rotating JS/WASM decryption, so we let a real WebView
 * run it and capture the final HLS URL when the page gives it to hls.js or requests it.
 */
@SuppressLint("StaticFieldLeak")
object FlixcloudBridge {
    private const val ORIGIN = "https://flixcloud.cc"
    private const val TAG = "FlixcloudBridge"
    private val FLIXCLOUD_ORIGIN_RULES = setOf(
        ORIGIN,
        "https://*.flixcloud.cc",
    )

    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()

    private class ResolveRequest(
        val id: String,
        val targetUrl: String,
        val deferred: CompletableDeferred<FlixcloudResolvedStream?> = CompletableDeferred(),
    ) {
        var scriptHandler: ScriptHandler? = null
        private val targetPage = targetUrl.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()

        fun ownsPage(url: String?): Boolean {
            val page = url?.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()
            return targetPage != null && page == targetPage
        }
    }

    @Volatile private var webView: WebView? = null
    @Volatile private var active: ResolveRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(wv: WebView, onRendererGone: (WebView) -> Unit = {}) {
        DiagnosticsLog.event("$TAG.attach")
        webView = wv
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            // This WebView only discovers the HLS URL. It must never become a second player.
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            userAgentString = userAgentString.replace("; wv", "")
        }
        wv.addJavascriptInterface(Bridge, "AndroidFlixcloud")
        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?,
            ): Boolean {
                DiagnosticsLog.event("$TAG blocked popup")
                return false
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url ?: return true
                if (!request.isForMainFrame) return false
                val host = target.host.orEmpty().lowercase()
                val allowed = target.scheme == "https" && (host == "flixcloud.cc" || host.endsWith(".flixcloud.cc"))
                if (!allowed) {
                    DiagnosticsLog.event(
                        "$TAG blocked nav ${privacySafeUrlDiagnosticLabel(target.toString())}",
                    )
                }
                return !allowed
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val request = active?.takeIf { it.ownsPage(url) } ?: return
                DiagnosticsLog.event(
                    "$TAG page finished ${privacySafeUrlDiagnosticLabel(url)}",
                )
                view?.evaluateJavascript(captureScript(request.id), null)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                webRequest: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = webRequest?.url?.toString().orEmpty()
                if (isHlsUrl(url) && isTrustedFlixcloudRequest(webRequest?.requestHeaders.orEmpty())) {
                    val current = active
                    main.postDelayed({
                        if (current != null && active === current) {
                            finishOnMain(current, FlixcloudResolvedStream(url, playlistKey = null), "request")
                        }
                    }, 250)
                } else if (isHlsUrl(url)) {
                    DiagnosticsLog.event("$TAG ignored HLS request without trusted provenance")
                }
                return null
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    DiagnosticsLog.event(
                        "$TAG main-frame error category=web-resource " +
                            "code=${error?.errorCode ?: "unknown"} mainFrame=true " +
                            privacySafeUrlDiagnosticLabel(request.url?.toString()),
                    )
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                DiagnosticsLog.event("$TAG render process gone didCrash=${detail?.didCrash()}")
                // Ignore a callback from an instance that has already been replaced.
                val gone = view ?: return true
                if (webView === gone) {
                    webView = null
                    active?.let { current ->
                        // The renderer is already dead, so do not call ScriptHandler.remove().
                        current.scriptHandler = null
                        finishOnMain(current, null, "render_gone")
                    }
                    runCatching { onRendererGone(gone) }
                        .onFailure { DiagnosticsLog.event("$TAG renderer recreation callback failed") }
                }
                return true
            }
        }
        wv.onPause()
    }

    fun detach(wv: WebView) {
        if (webView !== wv) return
        DiagnosticsLog.event("$TAG.detach")
        val request = active
        if (request != null) {
            finishOnMain(request, null, "detach")
        } else {
            stopPageOnMain(wv)
        }
        webView = null
        wv.removeJavascriptInterface("AndroidFlixcloud")
    }

    suspend fun resolve(
        embedUrl: String,
        referer: String?,
        timeoutMs: Long = 12_000,
    ): FlixcloudResolvedStream? = mutex.withLock {
        // The request id is also the bridge capability: third-party frames can see the bridge
        // name, but cannot guess the id injected into the trusted top-level document.
        val target = flixcloudCaptureUrl(embedUrl)
        val request = ResolveRequest(UUID.randomUUID().toString(), target)
        try {
            withContext(Dispatchers.Main.immediate) {
                // The mutex normally makes this unnecessary, but clearing any stale state here
                // guarantees that two resolver pages can never survive concurrently.
                active?.let { stale -> finishOnMain(stale, null, "superseded") }
                active = request
                loadWhenAttached(request, target, referer, SystemClock.elapsedRealtime() + timeoutMs)
            }
            val result = withTimeoutOrNull(timeoutMs) { request.deferred.await() }
            if (result == null && !request.deferred.isCompleted) {
                DiagnosticsLog.event("$TAG timeout ${privacySafeUrlDiagnosticLabel(target)}")
            }
            result?.takeIf { isHlsUrl(it.url) }
        } finally {
            // Cancellation must not skip cleanup. resolve() only returns after the hidden page is
            // paused and blanked, so native/embed fallback cannot overlap it.
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (active === request) finishOnMain(request, null, "cleanup")
            }
        }
    }

    private fun loadWhenAttached(
        request: ResolveRequest,
        target: String,
        referer: String?,
        deadlineMs: Long,
    ) {
        main.post(object : Runnable {
            override fun run() {
                if (active !== request) return
                val wv = webView
                if (wv != null) {
                    if (!installDocumentStartScript(wv, request)) {
                        finishOnMain(request, null, "document_start_unavailable")
                        return
                    }
                    DiagnosticsLog.event("$TAG load ${privacySafeUrlDiagnosticLabel(target)}")
                    wv.onResume()
                    wv.stopLoading()
                    wv.loadUrl(target, referer?.let { mapOf("Referer" to it) } ?: emptyMap())
                    return
                }
                if (SystemClock.elapsedRealtime() >= deadlineMs) {
                    finishOnMain(request, null, "not_attached_timeout")
                    return
                }
                main.postDelayed(this, 50)
            }
        })
    }

    object Bridge {
        @JavascriptInterface
        fun onResolved(id: String?, url: String?, playlistKey: String?) {
            if (id == null || url == null || !isHlsUrl(url)) return
            main.post {
                val request = active?.takeIf { it.id == id } ?: return@post
                finishOnMain(
                    request,
                    FlixcloudResolvedStream(url, playlistKey?.ifBlank { null }),
                    "js",
                )
            }
        }
    }

    /** Main-thread terminal transition: stop the page before exposing its result to the caller. */
    private fun finishOnMain(
        request: ResolveRequest,
        result: FlixcloudResolvedStream?,
        source: String,
    ) {
        if (active !== request) return
        runCatching { request.scriptHandler?.remove() }
        request.scriptHandler = null
        stopPageOnMain()
        active = null
        request.deferred.complete(result)
        if (result != null) {
            DiagnosticsLog.event(
                "$TAG resolved source=$source ${privacySafeUrlDiagnosticLabel(result.url)} " +
                    "playlistKey=${result.playlistKey != null}",
            )
        } else {
            DiagnosticsLog.event("$TAG finished source=$source result=none")
        }
    }

    private fun silencePageOnMain(wv: WebView? = webView) {
        val page = wv ?: return
        runCatching { page.evaluateJavascript(SILENCE_MEDIA_JS, null) }
    }

    /** Install before loadUrl so Flixcloud cannot make its one-shot HLS request first. */
    private fun installDocumentStartScript(wv: WebView, request: ResolveRequest): Boolean {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            DiagnosticsLog.event("$TAG document-start injection unsupported")
            return false
        }
        return runCatching {
            request.scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                wv,
                captureScript(request.id),
                FLIXCLOUD_ORIGIN_RULES,
            )
        }.onFailure {
            DiagnosticsLog.event("$TAG document-start injection failed type=${it.javaClass.simpleName}")
        }.isSuccess
    }

    private fun stopPageOnMain(wv: WebView? = webView) {
        val page = wv ?: return
        silencePageOnMain(page)
        runCatching { page.onPause() }
        runCatching { page.stopLoading() }
        runCatching { page.loadUrl("about:blank") }
    }

    private val SILENCE_MEDIA_JS = """
        (function() {
          function silence(media) {
            try {
              media.defaultMuted = true;
              media.muted = true;
              media.volume = 0;
              media.setAttribute('muted', '');
            } catch (e) {}
          }
          try {
            var proto = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
            if (proto && proto.play && !proto.play.__aniliSilenced) {
              var originalPlay = proto.play;
              var silentPlay = function() {
                silence(this);
                return originalPlay.apply(this, arguments);
              };
              silentPlay.__aniliSilenced = true;
              proto.play = silentPlay;
            }
            var media = document.querySelectorAll('video,audio');
            for (var i = 0; i < media.length; i++) silence(media[i]);
          } catch (e) {}
        })();
    """.trimIndent()

    private fun captureScript(id: String): String = """
        (function() {
          if (window.__aniliFlixHooked) return;
          window.__aniliFlixHooked = true;
          function silence(media) {
            try {
              media.defaultMuted = true;
              media.muted = true;
              media.volume = 0;
              media.setAttribute('muted', '');
            } catch (e) {}
          }
          function silenceMedia() {
            try {
              var media = document.querySelectorAll('video,audio');
              for (var i = 0; i < media.length; i++) silence(media[i]);
            } catch (e) {}
          }
          try {
            var mediaProto = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
            if (mediaProto && mediaProto.play && !mediaProto.play.__aniliSilenced) {
              var originalPlay = mediaProto.play;
              var silentPlay = function() {
                silence(this);
                return originalPlay.apply(this, arguments);
              };
              silentPlay.__aniliSilenced = true;
              mediaProto.play = silentPlay;
            }
          } catch (e) {}
          function report(value) {
            try {
              var url = String(value || '');
               if (url.indexOf('.m3u8') >= 0) {
                 AndroidFlixcloud.onResolved('$id', url, String(window.__pk || ''));
               }
            } catch (e) {}
          }
          function hookHls() {
            try {
              if (window.Hls && window.Hls.prototype && !window.Hls.prototype.__aniliFlixHooked) {
                var original = window.Hls.prototype.loadSource;
                window.Hls.prototype.__aniliFlixHooked = true;
                window.Hls.prototype.loadSource = function(source) {
                  silenceMedia();
                  report(source);
                  return original.apply(this, arguments);
                };
              }
            } catch (e) {}
          }
          try {
            var originalFetch = window.fetch;
            if (originalFetch && !originalFetch.__aniliFlixHooked) {
              var wrappedFetch = function(input, init) {
                report(typeof input === 'string' ? input : input && input.url);
                return originalFetch.apply(this, arguments).then(function(response) {
                  report(response && response.url);
                  return response;
                });
              };
              wrappedFetch.__aniliFlixHooked = true;
              window.fetch = wrappedFetch;
            }
          } catch (e) {}
          try {
            var originalOpen = XMLHttpRequest.prototype.open;
            if (originalOpen && !originalOpen.__aniliFlixHooked) {
              XMLHttpRequest.prototype.open = function(method, url) {
                report(url);
                return originalOpen.apply(this, arguments);
              };
              XMLHttpRequest.prototype.open.__aniliFlixHooked = true;
            }
          } catch (e) {}
          hookHls();
          silenceMedia();
          setInterval(function() { hookHls(); silenceMedia(); }, 100);
        })();
    """.trimIndent()

    private fun isHlsUrl(url: String): Boolean =
        url.contains(".m3u8", ignoreCase = true)

}

data class FlixcloudResolvedStream(val url: String, val playlistKey: String?)
