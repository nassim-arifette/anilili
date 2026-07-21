package com.miruronative.data.remote

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.diagnostics.privacySafeUrlDiagnosticLabel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Raw pipe response as returned by the in-page fetch, before deobfuscation. */
data class RawPipeResponse(val ok: Boolean, val status: Int, val obf: String?, val body: String?, val error: String?)

/**
 * Routes pipe requests through a real (hidden) WebView. Cloudflare fingerprints the HTTP client,
 * so a plain OkHttp call gets a 403 WAF block; a same-origin `fetch()` from inside a loaded
 * miruro.to page rides the browser's TLS fingerprint + `cf_clearance` cookie and is allowed.
 *
 * The WebView is created and attached to the window by [com.miruronative.ui.PipeWebView]; this
 * object owns the JS bridge and the request/response plumbing.
 */
@SuppressLint("StaticFieldLeak")
object PipeBridge {
    /**
     * Mirror domains, tried in order. ISPs block these piecemeal (user logs show .to timing out
     * on one network while others resolve), so a main-frame load failure rolls to the next
     * mirror instead of taking every pipe request down with it.
     */
    private val ORIGINS = listOf(
        "https://www.miruro.to",
        "https://www.miruro.tv",
        "https://www.miruro.bz",
    )

    private val main = Handler(Looper.getMainLooper())

    /**
     * The hidden tab hosts the full miruro SPA, whose scripts/animations otherwise run for the
     * whole session — on a Fire TV stick its software-layer draws even stalled the main thread
     * for seconds. Idle (View.onPause) the WebView when no pipe request has needed it for a
     * while; each fetch resumes it first. The CF session/cookies survive onPause just fine.
     */
    private const val IDLE_AFTER_MS = 60_000L
    private val idleRunnable = Runnable {
        attachment?.view?.onPause()
        DiagnosticsLog.event("PipeBridge webview idled")
    }

    private fun scheduleIdle() {
        main.removeCallbacks(idleRunnable)
        main.postDelayed(idleRunnable, IDLE_AFTER_MS)
    }

    private data class AttachedWebView(
        val view: WebView,
        val session: PipeRequestLifecycle.Session<String>,
        var readinessGeneration: Long,
        var originIndex: Int = 0,
    )

    private val lifecycle = PipeRequestLifecycle<String>()
    @Volatile private var attachment: AttachedWebView? = null

    private fun activeOrigin(attached: AttachedWebView): String = ORIGINS[attached.originIndex]

    private fun isCurrent(attached: AttachedWebView, callbackView: WebView? = attached.view): Boolean =
        callbackView === attached.view && attachment === attached && lifecycle.isCurrent(attached.session)

    /** Called from the hosting Composable on the main thread with a freshly created WebView. */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(wv: WebView, onRendererGone: () -> Unit = {}) {
        DiagnosticsLog.event("PipeBridge.attach")
        main.removeCallbacks(idleRunnable)
        val previous = attachment
        val transition = lifecycle.attach()
        val attached = AttachedWebView(wv, transition.session, transition.readinessGeneration)
        attachment = attached
        previous?.view?.removeJavascriptInterface("AndroidPipe")
        failRequests(transition.displacedRequests, "webview replaced")

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        wv.addJavascriptInterface(Bridge, "AndroidPipe")
        wv.webViewClient = object : WebViewClient() {
            // Pin the tab to Miruro's mirrors — block ad popunders / intent: redirects that
            // would navigate away and kill the same-origin pipe session.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return true
                val host = url.host.orEmpty().lowercase()
                val allowed = url.scheme == "https" && ORIGINS.any { origin ->
                    val originHost = origin.removePrefix("https://www.")
                    host == originHost || host.endsWith(".$originHost")
                }
                if (!allowed) {
                    val diagnosticUrl = privacySafeUrlDiagnosticLabel(url.toString())
                    DiagnosticsLog.event("PipeBridge blocked nav $diagnosticUrl")
                    Log.d(TAG, "blocked nav $diagnosticUrl")
                }
                return !allowed
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                DiagnosticsLog.event("PipeBridge page started ${privacySafeUrlDiagnosticLabel(url)}")
                if (isCurrent(attached, view)) {
                    lifecycle.beginNavigation(attached.session)?.let { navigation ->
                        attached.readinessGeneration = navigation.readinessGeneration
                        failRequests(navigation.displacedRequests, "webview navigated")
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val diagnosticUrl = privacySafeUrlDiagnosticLabel(url)
                DiagnosticsLog.event("PipeBridge page finished $diagnosticUrl")
                Log.d(TAG, "onPageFinished $diagnosticUrl")
                if (!isCurrent(attached, view)) return
                // Give Cloudflare a moment to settle, then allow fetches.
                val origin = activeOrigin(attached)
                if (url != null && (url == origin || url.startsWith("$origin/"))) {
                    val readinessGeneration = attached.readinessGeneration
                    main.postDelayed(
                        {
                            if (
                                isCurrent(attached) && lifecycle.markReady(
                                    attached.session,
                                    readinessGeneration,
                                    successful = true,
                                )
                            ) {
                                scheduleIdle()
                            }
                        },
                        2000,
                    )
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame != true || !isCurrent(attached, view)) return
                val origin = activeOrigin(attached)
                val failedUrl = request.url.toString()
                if (failedUrl != origin && !failedUrl.startsWith("$origin/")) return
                DiagnosticsLog.event(
                    "PipeBridge main-frame error category=web-resource " +
                        "code=${error?.errorCode ?: "unknown"} mainFrame=true " +
                        privacySafeUrlDiagnosticLabel(failedUrl),
                )
                // This mirror is unreachable (ISP block, DNS, site down): roll to the next one.
                // Only once all mirrors fail do waiters unblock into the cache/error path.
                if (attached.originIndex < ORIGINS.lastIndex) {
                    lifecycle.beginNavigation(attached.session)?.let { navigation ->
                        attached.readinessGeneration = navigation.readinessGeneration
                        failRequests(navigation.displacedRequests, "webview navigated")
                    }
                    attached.originIndex++
                    val nextOrigin = activeOrigin(attached)
                    DiagnosticsLog.event(
                        "PipeBridge trying mirror ${privacySafeUrlDiagnosticLabel(nextOrigin)}",
                    )
                    main.post {
                        if (isCurrent(attached)) attached.view.loadUrl("$nextOrigin/")
                    }
                } else {
                    lifecycle.markReady(
                        attached.session,
                        attached.readinessGeneration,
                        successful = false,
                    )
                }
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                DiagnosticsLog.event(
                    "PipeBridge render process gone didCrash=${detail?.didCrash()} " +
                        "priority=${detail?.rendererPriorityAtExit()}",
                )
                val callbackView = view ?: attached.view
                if (!isCurrent(attached, callbackView)) return true
                detach(callbackView)
                // The reported WebView can never be used again. Let the Compose host replace its
                // AndroidView after detach has failed any requests owned by this renderer.
                main.post(onRendererGone)
                return true
            }
        }
        val origin = activeOrigin(attached)
        DiagnosticsLog.event("PipeBridge load ${privacySafeUrlDiagnosticLabel(origin)}")
        wv.loadUrl("$origin/")
    }

    /** Releases the attached browser and fails requests that can no longer complete. */
    fun detach(wv: WebView) {
        val attached = attachment?.takeIf { it.view === wv } ?: return
        DiagnosticsLog.event("PipeBridge.detach")
        main.removeCallbacks(idleRunnable)
        attachment = null
        failRequests(lifecycle.detach(attached.session), "webview released")
        wv.removeJavascriptInterface("AndroidPipe")
    }

    object Bridge {
        @JavascriptInterface
        fun onResult(id: String?, json: String) {
            if (id != null) lifecycle.take(id)?.result?.complete(json)
        }
    }

    /** Runs `fetch('/api/secure/pipe?e=…')` inside the page and returns the raw JSON bridge payload. */
    suspend fun fetch(e: String, timeoutMs: Long = 30_000): String {
        val result = if (timeoutMs <= 0) {
            null
        } else {
            withTimeoutOrNull(timeoutMs) { fetchWithinDeadline(e) }
        } ?: run {
            DiagnosticsLog.event("PipeBridge fetch timeout e.len=${e.length}")
            errorPayload("timeout")
        }
        // Pipe responses may contain signed media URLs. Log sizes only, never response contents.
        Log.d(TAG, "fetch(e.len=${e.length}) result.len=${result.length}")
        return result
    }

    private suspend fun fetchWithinDeadline(e: String): String {
        while (true) {
            // The host intentionally creates resolver WebViews after a short startup delay. Wait
            // for that attachment inside fetch()'s caller-owned total deadline instead of turning
            // a fast deep link into a permanent provider failure.
            val session = lifecycle.awaitSession()
            val readiness = lifecycle.readinessSignal(session) ?: continue
            val readyResult = withTimeoutOrNull(READY_WAIT_TIMEOUT_MS) {
                readiness.result.await()
            }
            if (readyResult != true) {
                // Replacement, detach, and navigation wake the old signal so we can follow the
                // new owner. A failure that still owns the current document is terminal.
                if (!lifecycle.isCurrent(readiness)) continue
                val reason = if (readyResult == null) {
                    "webview readiness timeout"
                } else {
                    "webview unavailable"
                }
                DiagnosticsLog.event("PipeBridge $reason generation=${session.generation}")
                return errorPayload(reason)
            }

            if (!lifecycle.isCurrent(readiness)) continue
            val attached = attachment
                ?.takeIf { it.session === session && isCurrent(it) }
                ?: continue

            // The request id doubles as an unguessable capability. The bridge is visible to every
            // frame, but only the top-level injected script receives this value.
            val id = UUID.randomUUID().toString()
            val request = lifecycle.register(readiness, id) ?: continue

            val js = """
            (function(){
              try {
                fetch('/api/secure/pipe?e=$e', { headers: { 'Accept': '*/*' }, credentials: 'include' })
                  .then(function(r){
                    return r.text().then(function(b){
                      AndroidPipe.onResult('$id', JSON.stringify({
                        ok: r.ok, status: r.status,
                        obf: (r.headers.get('x-obfuscated') || ''), body: b
                      }));
                    });
                  })
                  .catch(function(err){
                    AndroidPipe.onResult('$id', JSON.stringify({ ok:false, status:-1, error:String(err) }));
                  });
              } catch (err) {
                AndroidPipe.onResult('$id', JSON.stringify({ ok:false, status:-1, error:String(err) }));
              }
            })();
            """.trimIndent()

            main.post {
                if (!lifecycle.isPending(request)) return@post
                if (!isCurrent(attached)) {
                    failRequest(request, "webview replaced")
                } else {
                    main.removeCallbacks(idleRunnable)
                    try {
                        attached.view.onResume()
                        attached.view.evaluateJavascript(js, null)
                        scheduleIdle()
                    } catch (error: Throwable) {
                        val errorType = error.javaClass.simpleName.ifBlank { "unknown" }
                        Log.w(TAG, "evaluateJavascript failed type=$errorType")
                        failRequest(request, "webview request failed")
                    }
                }
            }

            return try {
                request.result.await()
            } finally {
                // This also runs for caller cancellation and the outer request timeout. A posted
                // main-thread task checks ownership before injecting JavaScript.
                lifecycle.cancel(request)
            }
        }
    }

    private fun failRequest(request: PipeRequestLifecycle.Request<String>, reason: String) {
        if (lifecycle.cancel(request)) request.result.complete(errorPayload(reason))
    }

    private fun failRequests(requests: List<PipeRequestLifecycle.Request<String>>, reason: String) {
        val payload = errorPayload(reason)
        requests.forEach { it.result.complete(payload) }
    }

    private fun errorPayload(reason: String): String =
        """{"ok":false,"status":-1,"error":"$reason"}"""

    private const val READY_WAIT_TIMEOUT_MS = 25_000L
    private const val TAG = "PipeBridge"
}
