package com.miruronative.ui

import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.miruronative.data.remote.FlixcloudBridge
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.diagnostics.DiagnosticsLog
import com.miruronative.diagnostics.privacySafeUrlDiagnosticLabel

/**
 * Hidden resolver WebView for flixcloud embeds. It is not a player surface; it only lets
 * flixcloud's own JS/WASM produce the HLS URL so native playback can use it when possible.
 */
@Composable
fun FlixcloudResolverWebView() {
    var generation by remember { mutableIntStateOf(0) }
    key(generation) {
        var rendererGone by remember { mutableStateOf(false) }
        AndroidView(
            factory = { ctx ->
                try {
                    DiagnosticsLog.event("FlixcloudResolverWebView factory create WebView start")
                    DiagnosticsLog.webViewPackage("FlixcloudResolverWebView factory")
                    WebView(ctx).also { web ->
                        web.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        web.isFocusable = false
                        web.isClickable = false
                        // Software layer keeps this hidden helper off Chromium's SurfaceControl
                        // overlay path — see PipeWebView for details.
                        web.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        FlixcloudBridge.attach(web) { gone ->
                            if (gone === web && !rendererGone) {
                                rendererGone = true
                                generation += 1
                            }
                        }
                        DiagnosticsLog.event("FlixcloudResolverWebView factory create WebView complete")
                    }
                } catch (e: Throwable) {
                    CrashReporter.logNonFatal("System WebView unavailable; flixcloud native resolver disabled", e)
                    View(ctx)
                }
            },
            onRelease = { view ->
                val web = view as? WebView ?: return@AndroidView
                val releaseDetails = if (rendererGone) {
                    "renderer-gone"
                } else {
                    val url = runCatching { web.url }.getOrNull()
                    val size = runCatching { "${web.width}x${web.height}" }.getOrDefault("unknown")
                    "${privacySafeUrlDiagnosticLabel(url)} size=$size"
                }
                DiagnosticsLog.event(
                    "FlixcloudResolverWebView release rendererGone=$rendererGone " +
                        releaseDetails,
                )
                if (!rendererGone) {
                    FlixcloudBridge.detach(web)
                    runCatching { web.stopLoading() }
                    runCatching { web.webChromeClient = null }
                    runCatching { web.webViewClient = WebViewClient() }
                    runCatching { web.loadUrl("about:blank") }
                }
                // destroy() is the only safe WebView operation after onRenderProcessGone.
                runCatching { web.destroy() }
            },
            modifier = Modifier.size(1.dp),
        )
    }
}
