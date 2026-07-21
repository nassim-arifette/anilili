package com.miruronative.ui

import android.webkit.WebView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.size
import com.miruronative.data.remote.PipeBridge
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.diagnostics.privacySafeUrlDiagnosticLabel

/**
 * Hidden 1dp WebView that hosts the Cloudflare-cleared miruro.to session. It stays attached to the
 * window (so Chromium treats it like a real tab) but is effectively invisible. All pipe requests
 * run as same-origin fetches inside it — see [PipeBridge].
 *
 * A broken/disabled system WebView provider must not kill the whole app at first frame: the pipe
 * providers become unavailable, but AniList browsing and the native scrapers keep working.
 */
@Composable
fun PipeWebView() {
    var rendererGeneration by remember { mutableIntStateOf(0) }
    key(rendererGeneration) {
        AndroidView(
        factory = { ctx ->
            try {
                DiagnosticsLog.event("PipeWebView factory create WebView start")
                DiagnosticsLog.webViewPackage("PipeWebView factory")
                WebView(ctx).also {
                    it.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    it.isFocusable = false
                    it.isClickable = false
                    // Software layer keeps this hidden helper off Chromium's SurfaceControl
                    // overlay path: with "WebView overlays" enabled (server-side WebView flag),
                    // the hardware overlay of even a 1dp WebView can black out the whole window.
                    it.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    PipeBridge.attach(it) { rendererGeneration++ }
                    DiagnosticsLog.event("PipeWebView factory create WebView complete")
                }
            } catch (e: Throwable) {
                CrashReporter.logNonFatal("System WebView unavailable; pipe providers disabled", e)
                View(ctx)
            }
        },
        onRelease = { view ->
            val web = view as? WebView ?: return@AndroidView
            val releasedUrl = runCatching { web.url }.getOrNull()
            DiagnosticsLog.event(
                "PipeWebView release ${privacySafeUrlDiagnosticLabel(releasedUrl)} " +
                    "size=${web.width}x${web.height}",
            )
            PipeBridge.detach(web)
            runCatching { web.stopLoading() }
            runCatching { web.webChromeClient = null }
            runCatching { web.webViewClient = android.webkit.WebViewClient() }
            runCatching { web.destroy() }
        },
            modifier = Modifier.size(1.dp),
        )
    }
}
