package com.miruronative.ui.watch

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key as compositionKey
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.miruronative.data.model.AniSkipPlaybackSegment
import com.miruronative.data.model.SkipTimes
import com.miruronative.data.model.StreamItem
import com.miruronative.data.settings.CaptionEdgeStyle
import com.miruronative.data.settings.CaptionStyle
import com.miruronative.data.settings.SettingsStore
import com.miruronative.diagnostics.CrashReporter
import com.miruronative.diagnostics.DiagnosticsLog
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.rememberScreenReaderActive
import com.miruronative.ui.components.CaptionAppearanceDialog
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * Renders an embed/iframe provider (or the live site) in a WebView. This is both the player for
 * `type:"embed"` sources and the durable fallback when the native path is Cloudflare-blocked.
 *
 * Main-frame navigation is pinned to the embed's own host: ad-funded embed pages fire same-frame
 * redirects that would otherwise replace the video with an ad page. Resource and iframe loads are
 * unaffected, so the players themselves keep working.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbedWebView(
    url: String,
    referer: String?,
    playbackKey: EmbedPlaybackKey,
    modifier: Modifier = Modifier,
    playbackMode: EmbedPlaybackMode = EmbedPlaybackMode.MANAGED,
    qualityStreams: List<StreamItem> = emptyList(),
    startPositionMs: Long = 0L,
    skip: SkipTimes? = null,
    aniSkipSegments: List<AniSkipPlaybackSegment> = emptyList(),
    aniSkipLookupStatus: AniSkipLookupStatus = AniSkipLookupStatus.AWAITING_DURATION,
    seriesTitle: String? = null,
    episodeTitle: String? = null,
    onPreviousEpisode: ((EmbedPlaybackKey) -> Unit)? = null,
    onNextEpisode: ((EmbedPlaybackKey) -> Unit)? = null,
    hasPreviousEpisode: Boolean = false,
    hasNextEpisode: Boolean = false,
    focusPlayerOnStart: Boolean = true,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    onFullscreenChanged: (Boolean) -> Unit = {},
    onActiveMediaChanged: ((EmbedMediaIdentity) -> Unit)? = null,
    onProgress: ((EmbedMediaIdentity, Long, Long) -> Unit)? = null,
    onPlaybackEnded: ((EmbedPlaybackCompletion) -> Boolean)? = null,
    onPlaybackError: ((EmbedPlaybackKey, String, String, Long) -> Unit)? = null,
    onPlaybackStopperChanged: (((() -> Unit)?) -> Unit)? = null,
) {
    val device = LocalAppDeviceProfile.current
    val context = LocalContext.current
    val lifecycleOwner = context.findLifecycleOwner()
    DisposableEffect(Unit) { onDispose { resetPlayerBrightness(context) } }
    val embedQualityStreams = remember(playbackKey, url, qualityStreams) {
        qualityStreams
            .filter(StreamItem::isEmbed)
            .distinctBy { it.height ?: declaredVideoHeight(it.quality) }
            .filter { (it.height ?: declaredVideoHeight(it.quality)) != null }
            .sortedByDescending { it.height ?: declaredVideoHeight(it.quality) }
    }
    var activeUrl by remember(playbackKey, url) { mutableStateOf(url) }
    val activeQualityStream = embedQualityStreams.firstOrNull { it.url == activeUrl }
    val activeReferer = activeQualityStream?.referer ?: referer
    var captionAppearanceVisible by remember(playbackKey, url) { mutableStateOf(false) }
    var settingsSheetVisible by remember(playbackKey, url) { mutableStateOf(false) }
    var playbackSpeed by remember(playbackKey, url) { mutableStateOf(1f) }
    // The speed to restore once a hold-for-2x gesture ends (the user's chosen playback speed).
    var preHoldSpeed by remember(playbackKey, url) { mutableStateOf(1f) }
    var pendingSeekMs by remember(playbackKey, url, startPositionMs) { mutableLongStateOf(startPositionMs) }
    val allowedHost = remember(activeUrl) {
        runCatching { Uri.parse(activeUrl).host }.getOrNull()?.lowercase()?.removePrefix("www.")
    }
    val usesAllAnimeIframeShell = activeUrl.requiresAllAnimeIframeShell(activeReferer)
    val requestedDocumentUrl = if (usesAllAnimeIframeShell) activeReferer ?: activeUrl else activeUrl
    val navigationIdentity = EmbedNavigationIdentity(
        playbackKey = playbackKey,
        streamUrl = activeUrl,
        referer = activeReferer,
        usesIframeShell = usesAllAnimeIframeShell,
    )
    val navigationGuard = remember { EmbedNavigationGuard() }
    val documentTransitionGate = remember { EmbedDocumentTransitionGate() }
    var rendererGeneration by remember { mutableIntStateOf(0) }
    // remember() runs as soon as Compose observes a logical playback, URL, or referer change. This
    // revokes the preceding capability before AndroidView's later update reuses the WebView. A
    // renderer loss also gets a fresh session because Chromium forbids reusing the dead instance.
    val navigationSession = remember(navigationIdentity, rendererGeneration) {
        navigationGuard.begin(
            EmbedNavigationRequest(
                streamUrl = activeUrl,
                documentUrl = requestedDocumentUrl,
                allowedMainFrameHost = allowedHost,
                resumePositionMs = if (playbackMode.restoresPosition) pendingSeekMs else 0L,
            ),
        )
    }
    val activeMediaIdentity = remember(navigationIdentity, navigationSession.generation) {
        EmbedMediaIdentity(
            playbackKey = playbackKey,
            navigationGeneration = navigationSession.generation,
            mediaId = activeUrl,
        )
    }
    var webPlaybackAvailable by remember(navigationSession.generation) { mutableStateOf(false) }
    var loadError by remember(navigationSession.generation) { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var finishedUrl by remember(navigationSession.generation) { mutableStateOf<String?>(null) }
    val currentOnPlaybackStopperChanged by rememberUpdatedState(onPlaybackStopperChanged)
    val currentOnFullscreenChanged by rememberUpdatedState(onFullscreenChanged)
    val currentOnActiveMediaChanged by rememberUpdatedState(onActiveMediaChanged)
    val currentOnProgress by rememberUpdatedState(onProgress)
    val currentOnPlaybackEnded by rememberUpdatedState(onPlaybackEnded)
    val currentOnPreviousEpisode by rememberUpdatedState(onPreviousEpisode)
    val currentOnNextEpisode by rememberUpdatedState(onNextEpisode)
    val currentPlaybackKey by rememberUpdatedState(playbackKey)
    val currentHasPreviousEpisode by rememberUpdatedState(hasPreviousEpisode)
    val currentHasNextEpisode by rememberUpdatedState(hasNextEpisode)
    // The bridge is process-wide and visible to every frame. Its random capability prevents a
    // third-party iframe from forging callbacks; the navigation token rejects superseded pages.
    val progressBridgeToken = remember { UUID.randomUUID().toString() }
    val currentNavigationSession by rememberUpdatedState(navigationSession)
    val currentActiveMediaIdentity by rememberUpdatedState(activeMediaIdentity)
    // The WebView is built once, so the remote handler below has to read this live rather than
    // capture the value it was created with.
    val currentPlayerOwnsRemote by rememberUpdatedState(focusPlayerOnStart)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val commandCoordinator = remember { EmbedCommandCoordinator() }
    val pendingCommandCallbacks = remember {
        mutableMapOf<Long, (EmbedCommandResolution) -> Unit>()
    }
    var positionMs by remember(playbackKey, url) { mutableLongStateOf(startPositionMs) }
    var durationMs by remember(playbackKey, url) { mutableLongStateOf(0L) }
    var webIsPlaying by remember(playbackKey, url) { mutableStateOf(false) }
    var webMediaIdentity by remember(playbackKey, url) { mutableStateOf<String?>(null) }
    var webVolume by remember(playbackKey, url) { mutableStateOf(1f) }
    var lastAudibleVolume by remember(playbackKey, url) { mutableStateOf(1f) }
    // Cross-origin embeds (some Kiwi mirrors) put the video out of the injected JS's reach, so
    // web-volume calls silently do nothing and a muted page stays muted. The device media
    // stream is always controllable; it becomes the volume/mute fallback for those servers.
    val embedAudioManager = remember(context) {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
    }
    var deviceVolume by remember { mutableStateOf(readDeviceVolume(embedAudioManager)) }
    var tvControlsVisible by remember(playbackKey, url) { mutableStateOf(false) }
    var tvControlsInteraction by remember(playbackKey, url) { mutableIntStateOf(0) }
    var touchControlsVisible by remember(playbackKey, url) { mutableStateOf(true) }
    var touchControlsInteraction by remember(playbackKey, url) { mutableIntStateOf(0) }
    var fallbackControlsVisible by remember(playbackKey, url) { mutableStateOf(true) }
    var fallbackInteraction by remember(playbackKey, url) { mutableIntStateOf(0) }
    // While set, the page owns touches again: our overlay steps aside so the provider's own bar —
    // the only scrubber those servers give us — is reachable, then we take the screen back.
    var providerControlsMode by remember(playbackKey, url) { mutableStateOf(false) }
    val tvPlayPauseFocus = remember { FocusRequester() }
    val currentPositionMs by rememberUpdatedState(positionMs)
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)
    val autoSkipIntroOutro by SettingsStore.autoSkipIntroOutro.collectAsState()
    val autoplay by SettingsStore.autoplay.collectAsState()
    val captionStyle by SettingsStore.captionStyle.collectAsState()
    LaunchedEffect(activeMediaIdentity, playbackMode.exposesNativeBridge) {
        if (playbackMode.exposesNativeBridge) {
            currentOnActiveMediaChanged?.invoke(activeMediaIdentity)
        }
    }
    val skipPlan = remember(skip, aniSkipSegments, aniSkipLookupStatus) {
        buildPlaybackSkipPlan(skip, aniSkipSegments, aniSkipLookupStatus)
    }
    val automaticOpening = skipPlan.automaticOpening
    val automaticEnding = skipPlan.automaticEnding
    val introStartMs = automaticOpening?.startMs ?: 0L
    val introEndMs = automaticOpening?.endMs
    val outroStartMs = automaticEnding?.startMs
    val outroEndMs = automaticEnding?.endMs
    var introAutoSkipped by remember(navigationSession.generation, introStartMs, introEndMs) {
        mutableStateOf(false)
    }
    var introAutoSkipPending by remember(navigationSession.generation, introStartMs, introEndMs) {
        mutableStateOf(false)
    }
    var introAutoSkipAttempt by remember(navigationSession.generation, introStartMs, introEndMs) {
        mutableIntStateOf(0)
    }
    var outroAutoHandled by remember(navigationSession.generation, outroStartMs, outroEndMs) {
        mutableStateOf(false)
    }
    var outroAutoSkipPending by remember(navigationSession.generation, outroStartMs, outroEndMs) {
        mutableStateOf(false)
    }
    var outroAutoSkipAttempt by remember(navigationSession.generation, outroStartMs, outroEndMs) {
        mutableIntStateOf(0)
    }
    val autoAdvanceGate = remember { EmbedAutoAdvanceGate() }
    // The concrete WebView and Java bridge live across URL changes. Updated handlers make their
    // callbacks target the current navigation's state, while the bridge token rejects old pages.
    val currentWebTickHandler by rememberUpdatedState<(
        String, Double, Double, Boolean, Boolean, Double, String,
    ) -> Unit>(
        newValue = { navigationToken, positionSec, durationSec, isPlaying, muted, volume, mediaIdentity ->
            if (
                navigationGuard.acceptsBridgeToken(navigationToken) &&
                positionSec >= 0 &&
                durationSec > 0
            ) {
                val nextPositionMs = (positionSec * 1000).toLong()
                val nextDurationMs = (durationSec * 1000).toLong()
                positionMs = nextPositionMs
                durationMs = nextDurationMs
                webIsPlaying = isPlaying
                webMediaIdentity = mediaIdentity.takeIf(String::isNotBlank)
                webVolume = if (muted) 0f else volume.toFloat().coerceIn(0f, 1f)
                if (playbackMode.exposesNativeBridge && isPlaying && nextPositionMs > 0L) {
                    currentOnProgress?.invoke(
                        currentActiveMediaIdentity,
                        nextPositionMs,
                        nextDurationMs,
                    )
                }
            }
        },
    )
    val currentWebVideoAvailableHandler by rememberUpdatedState<(String) -> Unit> { navigationToken ->
        if (navigationGuard.acceptsBridgeToken(navigationToken)) webPlaybackAvailable = true
    }
    val currentWebEndedHandler by rememberUpdatedState<(String, Double, Double, Int) -> Unit>(
        newValue = { navigationToken, positionSec, durationSec, observedPlayingSamples ->
            if (!navigationGuard.acceptsBridgeToken(navigationToken)) {
                DiagnosticsLog.event("EmbedWebView ignored stale ended token=$navigationToken")
            } else {
                val sample = embedEndSampleFromSeconds(positionSec, durationSec, observedPlayingSamples)
                if (sample == null || !isLikelyEmbedContentEnd(sample)) {
                    DiagnosticsLog.event(
                        "EmbedWebView ignored non-content end token=$navigationToken " +
                            "durationSec=$durationSec samples=$observedPlayingSamples",
                    )
                } else {
                    webIsPlaying = false
                    positionMs = sample.positionMs
                    durationMs = sample.durationMs
                    val completion = EmbedPlaybackCompletion(
                        playbackKey = playbackKey,
                        reportedPositionMs = sample.positionMs,
                        durationMs = sample.durationMs,
                        observedPlayingSamples = sample.observedPlayingSamples,
                    )
                    val committed = finalizeEmbedCompletionThenNavigate(
                        completion = completion,
                        shouldNavigate =
                            autoplay && currentHasNextEpisode && currentOnNextEpisode != null,
                        commit = currentOnPlaybackEnded ?: { false },
                        navigate = {
                            if (
                                autoAdvanceGate.tryAdvance(
                                    navigationToken = navigationToken,
                                    autoplay = true,
                                    hasNextEpisode = true,
                                )
                            ) {
                                DiagnosticsLog.event(
                                    "EmbedWebView auto advance reason=ended token=$navigationToken",
                                )
                                currentOnNextEpisode?.invoke(playbackKey)
                            }
                        },
                    )
                    if (!committed) {
                        DiagnosticsLog.event(
                            "EmbedWebView withheld autoplay after uncommitted end token=$navigationToken",
                        )
                    }
                }
            }
        },
    )
    val currentWebCommandHandler by rememberUpdatedState<(
        String,
        String,
        Boolean,
        Double,
        Boolean,
        String,
    ) -> Unit>(
        newValue = { navigationToken, commandId, succeeded, positionSec, isPlaying, mediaIdentity ->
            if (!navigationGuard.acceptsBridgeToken(navigationToken)) {
                DiagnosticsLog.event("EmbedWebView ignored stale command ack token=$navigationToken")
            } else {
                val id = commandId.toLongOrNull()
                val generation = navigationToken.toLongOrNull()
                if (id != null && generation != null) {
                    val resolution = commandCoordinator.acknowledge(
                        EmbedCommandAcknowledgement(
                            commandId = id,
                            navigationGeneration = generation,
                            succeeded = succeeded,
                            positionMs = (positionSec * 1_000.0).toLong().coerceAtLeast(0L),
                            isPlaying = isPlaying,
                            mediaIdentity = mediaIdentity,
                        ),
                        nowMs = SystemClock.uptimeMillis(),
                    )
                    if (resolution !is EmbedCommandResolution.Ignored) {
                        pendingCommandCallbacks.remove(id)?.invoke(resolution)
                    }
                }
            }
        },
    )
    val requestSeek: (Long?, ((Boolean) -> Unit)?) -> Boolean = { target, onResult ->
        if (target == null) false else dispatchWebCommand(
            webView = webView,
            session = navigationSession,
            guard = navigationGuard,
            coordinator = commandCoordinator,
            callbacks = pendingCommandCallbacks,
            handler = mainHandler,
            kind = EmbedCommandKind.SEEK,
            mediaIdentity = webMediaIdentity,
            script = { commandId ->
                seekVideoCommandJs(
                    targetSec = target / 1_000.0,
                    navigationGeneration = navigationSession.generation,
                    capabilityToken = progressBridgeToken,
                    commandId = commandId,
                    expectedMediaIdentity = webMediaIdentity,
                )
            },
            onResolved = { resolution ->
                val acknowledgement = (resolution as? EmbedCommandResolution.Confirmed)
                    ?.acknowledgement
                val confirmed = acknowledgement?.succeeded == true
                if (confirmed) positionMs = acknowledgement.positionMs
                onResult?.invoke(confirmed)
            },
        )
    }
    val requestTogglePlayback: () -> Boolean = {
        dispatchWebCommand(
            webView = webView,
            session = navigationSession,
            guard = navigationGuard,
            coordinator = commandCoordinator,
            callbacks = pendingCommandCallbacks,
            handler = mainHandler,
            kind = EmbedCommandKind.TOGGLE_PLAYBACK,
            mediaIdentity = webMediaIdentity,
            script = { commandId ->
                togglePlaybackCommandJs(
                    navigationGeneration = navigationSession.generation,
                    capabilityToken = progressBridgeToken,
                    commandId = commandId,
                    expectedMediaIdentity = webMediaIdentity,
                )
            },
            onResolved = { resolution ->
                val acknowledgement = (resolution as? EmbedCommandResolution.Confirmed)
                    ?.acknowledgement
                if (acknowledgement?.succeeded == true) {
                    webIsPlaying = acknowledgement.isPlaying
                    positionMs = acknowledgement.positionMs
                }
            },
        )
    }
    val currentRevealTvControls by rememberUpdatedState<(String) -> Unit> { navigationToken ->
        if (navigationGuard.acceptsBridgeToken(navigationToken)) {
            tvControlsVisible = true
            tvControlsInteraction++
        }
    }
    val currentToggleWebPlayback by rememberUpdatedState<(String) -> Unit> { navigationToken ->
        if (navigationGuard.acceptsBridgeToken(navigationToken)) requestTogglePlayback()
    }

    DisposableEffect(webView) {
        val web = webView
        currentOnPlaybackStopperChanged?.invoke(web?.let { { stopWebPlayback(it) } })
        onDispose { currentOnPlaybackStopperChanged?.invoke(null) }
    }

    val canControlPlayback = playbackMode.controlsPlayback && webPlaybackAvailable
    val canAutomatePlayback = canControlPlayback && playbackMode.automatesEpisode
    // Full touch controls — seek bar and all — whenever the injected JS can reach the <video>.
    val touchControlsActive = canControlPlayback && !device.isTv && loadError == null
    // Everything else: the page is out of reach, so this bar carries only what the app itself can
    // answer — episode moves, device-volume settings, fullscreen, and an honest provider hand-off.
    val fallbackControlsActive =
        playbackMode.controlsPlayback && !device.isTv && !webPlaybackAvailable && loadError == null

    LaunchedEffect(
        navigationSession.generation,
        touchControlsActive,
        touchControlsVisible,
        touchControlsInteraction,
        webIsPlaying,
    ) {
        if (!touchControlsActive || !touchControlsVisible || !webIsPlaying) return@LaunchedEffect
        delay(4_000)
        touchControlsVisible = false
    }

    LaunchedEffect(
        navigationSession.generation,
        fallbackControlsActive,
        fallbackControlsVisible,
        fallbackInteraction,
    ) {
        if (!fallbackControlsActive || !fallbackControlsVisible) return@LaunchedEffect
        delay(4_000)
        fallbackControlsVisible = false
    }
    // Meet the video with the bar once the page is up, the way the first tap would.
    LaunchedEffect(navigationSession.generation, fallbackControlsActive, finishedUrl) {
        if (fallbackControlsActive && finishedUrl != null) fallbackControlsVisible = true
    }
    // The provider's chrome hides itself a few seconds after the touch that raised it, so take
    // the screen back on roughly that beat.
    LaunchedEffect(navigationSession.generation, providerControlsMode) {
        if (!providerControlsMode) return@LaunchedEffect
        delay(8_000)
        providerControlsMode = false
    }

    LaunchedEffect(navigationSession.generation, tvControlsVisible, focusPlayerOnStart) {
        if (!tvControlsVisible || !focusPlayerOnStart) return@LaunchedEffect
        delay(32)
        runCatching { tvPlayPauseFocus.requestFocus() }
    }
    val screenReaderActive = rememberScreenReaderActive()
    // TalkBack users can't discover the hidden control row through a key press, so present it
    // as soon as the fullscreen player opens instead of waiting for the semantic reveal action.
    LaunchedEffect(screenReaderActive, focusPlayerOnStart, navigationSession.generation) {
        if (device.isTv && screenReaderActive && focusPlayerOnStart) {
            tvControlsVisible = true
        }
    }
    LaunchedEffect(
        navigationSession.generation,
        tvControlsVisible,
        tvControlsInteraction,
        webView,
        focusPlayerOnStart,
        screenReaderActive,
        settingsSheetVisible,
        captionAppearanceVisible,
    ) {
        if (!focusPlayerOnStart) {
            tvControlsVisible = false
            return@LaunchedEffect
        }
        if (!tvControlsVisible) return@LaunchedEffect
        // TalkBack users navigate slowly and can't reopen the controls with a key press
        // (the screen reader consumes the D-pad), so never auto-hide under a screen reader.
        if (screenReaderActive) return@LaunchedEffect
        // While the settings panel or caption dialog is up, hiding the row would hand focus
        // back to the WebView and take the remote away from the panel.
        if (settingsSheetVisible || captionAppearanceVisible) return@LaunchedEffect
        delay(8_000)
        tvControlsVisible = false
        webView?.requestFocus()
    }

    LaunchedEffect(
        navigationSession.generation,
        webView,
        device.isTv,
        focusPlayerOnStart,
        hasPreviousEpisode,
        hasNextEpisode,
        screenReaderActive,
    ) {
        if (!device.isTv || !focusPlayerOnStart || webView == null) return@LaunchedEffect
        // Under a screen reader the control row is auto-shown and focused; grabbing focus for
        // the WebView here would dump TalkBack back into the embed's web content.
        if (screenReaderActive) return@LaunchedEffect
        delay(250)
        runCatching { webView?.requestFocus() }
    }

    LaunchedEffect(navigationSession.generation) {
        DiagnosticsLog.event(
            "EmbedWebView composed urlHost=${allowedHost ?: "unknown"} " +
                "generation=${navigationSession.generation} refererHost=${activeReferer.hostOrNone()}",
        )
        delay(10_000)
        if (finishedUrl == null && loadError == null) {
            DiagnosticsLog.event("EmbedWebView still loading after 10000ms urlHost=${allowedHost ?: "unknown"}")
        }
    }
    // The navigation session locks its main frame after the accepted finish callback: later
    // top-frame requests are ad hijacks, including same-host popunder gateways.
    // Compare navigation generations, not WebView.url: pages rewrite their own URL
    // (history.replaceState), which must not re-trigger the explicit app navigation.
    val lastRequestedGeneration = remember { object { var value: Long = 0L } }

    LaunchedEffect(webPlaybackAvailable, playbackSpeed, webView, navigationSession.generation) {
        val web = webView ?: return@LaunchedEffect
        if (!webPlaybackAvailable || !navigationGuard.isCurrent(navigationSession)) return@LaunchedEffect
        web.evaluateJavascript(
            SET_PLAYBACK_SPEED_JS(playbackSpeed, navigationSession.generation),
            null,
        )
    }

    // Best-effort: reaches the main document and same-origin iframes only, exactly like the
    // progress poll. A cross-origin embed renders its own captions out of our reach, and some
    // providers burn subtitles into the video, where there is nothing to style at all.
    LaunchedEffect(webPlaybackAvailable, captionStyle, webView, navigationSession.generation) {
        val web = webView ?: return@LaunchedEffect
        if (!webPlaybackAvailable || !navigationGuard.isCurrent(navigationSession)) return@LaunchedEffect
        web.evaluateJavascript(
            CAPTION_STYLE_JS(captionStyle, navigationSession.generation),
            null,
        )
    }

    // Once the app can identify and command the content video, it owns the control surface. Hide
    // only known provider control bars (and the selected video's native controls) so the two sets
    // are not drawn on top of each other. Cross-origin players never reach this state and retain
    // their own controls.
    LaunchedEffect(touchControlsActive, webView, navigationSession.generation) {
        val web = webView ?: return@LaunchedEffect
        if (!navigationGuard.isCurrent(navigationSession)) return@LaunchedEffect
        web.evaluateJavascript(
            providerControlsVisibilityJs(
                hidden = touchControlsActive,
                navigationGeneration = navigationSession.generation,
            ),
            null,
        )
    }

    DisposableEffect(lifecycleOwner, webView) {
        val owner = lifecycleOwner
        val web = webView
        if (owner == null || web == null) {
            onDispose {}
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE,
                    Lifecycle.Event.ON_STOP -> {
                        runCatching { web.evaluateJavascript(PAUSE_EMBED_MEDIA_JS, null) }
                        // Keep lifecycle changes instance-local; timers are global to all WebViews.
                        web.onPause()
                    }
                    else -> if (shouldResumeEmbedForLifecycleEvent(event)) {
                        web.onResume()
                    }
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }
    }

    LaunchedEffect(
        autoSkipIntroOutro,
        autoplay,
        webIsPlaying,
        webView,
        positionMs,
        introStartMs,
        introEndMs,
        outroStartMs,
        outroEndMs,
        navigationSession.generation,
        hasNextEpisode,
        introAutoSkipAttempt,
        outroAutoSkipAttempt,
        canAutomatePlayback,
    ) {
        if (
            !canAutomatePlayback ||
            !autoSkipIntroOutro ||
            !webIsPlaying ||
            positionMs <= 0L
        ) {
            return@LaunchedEffect
        }

        if (!introAutoSkipped && isInSkipWindow(positionMs, introStartMs, introEndMs)) {
            if (!introAutoSkipPending) {
                introAutoSkipPending = true
                val queued = requestSeek(
                    introEndMs,
                    seekResult@{ succeeded ->
                        if (!navigationGuard.isCurrent(navigationSession)) return@seekResult
                        introAutoSkipPending = false
                        if (succeeded) {
                            introAutoSkipped = true
                        } else {
                            mainHandler.postDelayed(
                                {
                                    if (
                                        navigationGuard.isCurrent(navigationSession) &&
                                        !introAutoSkipped
                                    ) {
                                        introAutoSkipAttempt++
                                    }
                                },
                                AUTO_SKIP_RETRY_MS,
                            )
                        }
                    },
                )
                if (!queued) {
                    introAutoSkipPending = false
                    mainHandler.postDelayed(
                        {
                            if (navigationGuard.isCurrent(navigationSession) && !introAutoSkipped) {
                                introAutoSkipAttempt++
                            }
                        },
                        AUTO_SKIP_RETRY_MS,
                    )
                }
            }
            return@LaunchedEffect
        }

        when (
            outroSkipAction(
                autoSkip = autoSkipIntroOutro,
                autoplay = autoplay,
                hasNextEpisode = currentHasNextEpisode && currentOnNextEpisode != null,
                isPlaying = webIsPlaying,
                alreadyHandled = outroAutoHandled,
                positionMs = positionMs,
                startMs = outroStartMs,
                endMs = outroEndMs,
            )
        ) {
            OutroSkipAction.NONE -> Unit
            OutroSkipAction.SEEK_TO_END -> {
                if (!outroAutoSkipPending) {
                    outroAutoSkipPending = true
                    val queued = requestSeek(
                        outroEndMs,
                        seekResult@{ succeeded ->
                            if (!navigationGuard.isCurrent(navigationSession)) return@seekResult
                            outroAutoSkipPending = false
                            if (succeeded) {
                                outroAutoHandled = true
                            } else {
                                mainHandler.postDelayed(
                                    {
                                        if (
                                            navigationGuard.isCurrent(navigationSession) &&
                                            !outroAutoHandled
                                        ) {
                                            outroAutoSkipAttempt++
                                        }
                                    },
                                    AUTO_SKIP_RETRY_MS,
                                )
                            }
                        },
                    )
                    if (!queued) {
                        outroAutoSkipPending = false
                        mainHandler.postDelayed(
                            {
                                if (navigationGuard.isCurrent(navigationSession) && !outroAutoHandled) {
                                    outroAutoSkipAttempt++
                                }
                            },
                            AUTO_SKIP_RETRY_MS,
                        )
                    }
                }
            }
            OutroSkipAction.NEXT_EPISODE -> {
                if (
                    autoAdvanceGate.tryAdvance(
                        navigationToken = navigationSession.bridgeToken,
                        autoplay = autoplay,
                        hasNextEpisode = currentHasNextEpisode && currentOnNextEpisode != null,
                    )
                ) {
                    outroAutoHandled = true
                    currentOnNextEpisode?.invoke(playbackKey)
                }
            }
        }
    }

    val remoteModifier = if (playbackMode.controlsPlayback && device.isTv && focusPlayerOnStart) {
        Modifier
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !opensTvPlayerControls(event.key)) {
                    return@onPreviewKeyEvent false
                }
                tvControlsInteraction++
                false
            }
            // Screen readers swallow the D-pad, so the WebView key hook never fires under
            // TalkBack; this semantic action is the accessible way to reveal the controls.
            .semantics {
                contentDescription = "Video player"
                onClick(label = "Show player controls") {
                    tvControlsInteraction++
                    tvControlsVisible = true
                    true
                }
            }
    } else {
        Modifier
    }
    Box(modifier.then(remoteModifier)) {
        if (captionAppearanceVisible) {
            CaptionAppearanceDialog(
                onDismiss = { captionAppearanceVisible = false },
                footnote = "This server renders its own subtitles, so it may ignore some of these.",
            )
        }
        compositionKey(rendererGeneration, playbackMode) {
            AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                try {
                    DiagnosticsLog.event("EmbedWebView factory create WebView")
                    DiagnosticsLog.webViewPackage("EmbedWebView factory")
                    object : WebView(ctx) {
                        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                            // Only claim the remote while the player is the active surface. In the
                            // TV episode grid the list owns the D-pad: handling keys here ate them
                            // silently (the controls this opens are hidden in that state) and stole
                            // Center from the box that opens the player fullscreen. Report them
                            // unhandled rather than deferring to super, which would let the embed's
                            // own page act on the remote; unhandled keys let the framework move
                            // focus out of the player and back to the list.
                            if (device.isTv && !currentPlayerOwnsRemote) return false
                            if (
                                playbackMode.controlsPlayback &&
                                event.action == KeyEvent.ACTION_DOWN &&
                                event.repeatCount == 0
                            ) {
                                when {
                                    device.isTv && (
                                        event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                                            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                                            event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                                        event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                                        ) -> {
                                        val navigationToken = currentNavigationSession.bridgeToken
                                        mainHandler.post { currentRevealTvControls(navigationToken) }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                        event.keyCode == KeyEvent.KEYCODE_ENTER ||
                                        event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                        val navigationToken = currentNavigationSession.bridgeToken
                                        mainHandler.post { currentToggleWebPlayback(navigationToken) }
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT && currentHasNextEpisode -> {
                                        currentOnNextEpisode?.invoke(currentPlaybackKey)
                                        return true
                                    }
                                    event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS && currentHasPreviousEpisode -> {
                                        currentOnPreviousEpisode?.invoke(currentPlaybackKey)
                                        return true
                                    }
                                }
                            }
                            return super.dispatchKeyEvent(event)
                        }
                    }.apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        setBackgroundColor(android.graphics.Color.BLACK)
                        isFocusable = !device.isTv || focusPlayerOnStart
                        isFocusableInTouchMode = !device.isTv || focusPlayerOnStart
                        with(settings) {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            allowFileAccess = false
                            allowContentAccess = false
                            // Route window.open through onCreateWindow (dropped there) instead
                            // of letting popunders replace the player in the main frame.
                            setSupportMultipleWindows(true)
                            javaScriptCanOpenWindowsAutomatically = false
                            userAgentString = userAgentString.replace("; wv", "") // look less like a webview
                        }
                        // Construct the concrete bridge at registration so Android lint can
                        // verify its @JavascriptInterface methods through Kotlin's apply block.
                        if (playbackMode.exposesNativeBridge) {
                            addJavascriptInterface(
                                WebProgressBridge(
                                    expectedToken = progressBridgeToken,
                                    onTickCallback = {
                                            navigationToken,
                                            positionSec,
                                            durationSec,
                                            isPlaying,
                                            muted,
                                            volume,
                                            mediaIdentity,
                                        ->
                                        mainHandler.post {
                                            currentWebTickHandler(
                                                navigationToken,
                                                positionSec,
                                                durationSec,
                                                isPlaying,
                                                muted,
                                                volume,
                                                mediaIdentity,
                                            )
                                        }
                                    },
                                    onVideoAvailableCallback = { navigationToken ->
                                        mainHandler.post { currentWebVideoAvailableHandler(navigationToken) }
                                    },
                                    onEndedCallback = { navigationToken, positionSec, durationSec, playingSamples ->
                                        mainHandler.post {
                                            currentWebEndedHandler(
                                                navigationToken,
                                                positionSec,
                                                durationSec,
                                                playingSamples,
                                            )
                                        }
                                    },
                                    onCommandResultCallback = {
                                            navigationToken,
                                            commandId,
                                            succeeded,
                                            positionSec,
                                            isPlaying,
                                            mediaIdentity,
                                        ->
                                        mainHandler.post {
                                            currentWebCommandHandler(
                                                navigationToken,
                                                commandId,
                                                succeeded,
                                                positionSec,
                                                isPlaying,
                                                mediaIdentity,
                                            )
                                        }
                                    },
                                ),
                                "AniliProgress",
                            )
                        }
                        webViewClient = WebViewClient()
                        webChromeClient = WebChromeClient()
                        webView = this
                        DiagnosticsLog.event("EmbedWebView factory complete userAgent=${settings.userAgentString.take(100)}")
                    }
                } catch (e: Throwable) {
                    CrashReporter.logNonFatal("System WebView unavailable; embed player disabled", e)
                    View(ctx).apply { setBackgroundColor(android.graphics.Color.BLACK) }
                }
            },
            update = { view ->
                val web = view as? WebView ?: return@AndroidView
                web.isFocusable = !device.isTv || focusPlayerOnStart
                web.isFocusableInTouchMode = !device.isTv || focusPlayerOnStart
                if (device.isTv && !focusPlayerOnStart) web.clearFocus()
                val headers = activeReferer?.let { mapOf("Referer" to it) } ?: emptyMap()
                if (lastRequestedGeneration.value != navigationSession.generation) {
                    lastRequestedGeneration.value = navigationSession.generation
                    web.tag = navigationSession
                    webPlaybackAvailable = false
                    webIsPlaying = false
                    webMediaIdentity = null
                    loadError = null
                    finishedUrl = null
                    documentTransitionGate.begin(navigationSession)

                    val handleRendererGone = { goneView: WebView?, detail: RenderProcessGoneDetail? ->
                        DiagnosticsLog.event(
                            "EmbedWebView render process gone generation=${navigationSession.generation} " +
                                "didCrash=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()}",
                        )
                        // Revoke the dead document immediately. Changing both keys disposes this
                        // AndroidView and creates a fresh Chromium renderer + navigation capability.
                        documentTransitionGate.invalidate(navigationSession)
                        navigationGuard.invalidate(navigationSession)
                        if (webView === goneView || goneView == null) webView = null
                        rendererGeneration++
                        currentOnPlaybackError?.invoke(
                            playbackKey,
                            "Video server renderer stopped",
                            navigationSession.request.streamUrl,
                            currentPositionMs,
                        )
                        Unit
                    }

                    fun loadRequestedDocument(targetWeb: WebView) {
                        if (
                            targetWeb.tag !== navigationSession ||
                            !navigationGuard.isCurrent(navigationSession)
                        ) {
                            DiagnosticsLog.event(
                                "EmbedWebView ignored stale post-blank load " +
                                    "generation=${navigationSession.generation}",
                            )
                            return
                        }
                        targetWeb.webViewClient = EmbedNavigationWebViewClient(
                            session = navigationSession,
                            guard = navigationGuard,
                            onPageStartedAccepted = { startedUrl ->
                                loadError = null
                                finishedUrl = null
                                DiagnosticsLog.event(
                                    "EmbedWebView page started generation=${navigationSession.generation} " +
                                        "host=${startedUrl.hostOrNone()}",
                                )
                            },
                            onPageFinishedAccepted = { finishedView, loadedUrl ->
                                finishedUrl = loadedUrl
                                DiagnosticsLog.event(
                                    "EmbedWebView page finished generation=${navigationSession.generation} " +
                                        "host=${loadedUrl.hostOrNone()} title=${finishedView.title ?: "none"}",
                                )
                                if (playbackMode.exposesNativeBridge) {
                                    val navigationSetupJs = buildString {
                                        appendLine(
                                            authenticatedProgressPollJs(
                                                navigationSession.generation,
                                                progressBridgeToken,
                                            ),
                                        )
                                        if (navigationSession.request.resumePositionMs > 0L) {
                                            append(
                                                embedResumeWhenReadyJs(
                                                    navigationSession.request.resumePositionMs / 1000.0,
                                                    navigationSession.generation,
                                                ),
                                            )
                                        }
                                    }
                                    // One evaluation preserves setup order: the document receives
                                    // its capability before the capability-scoped resume timer.
                                    finishedView.evaluateJavascript(navigationSetupJs, null)
                                }
                            },
                            onMainFrameErrorAccepted = { message ->
                                loadError = message
                                currentOnPlaybackError?.invoke(
                                    playbackKey,
                                    message,
                                    navigationSession.request.streamUrl,
                                    currentPositionMs,
                                )
                            },
                            onRenderProcessGoneAccepted = handleRendererGone,
                        )
                        targetWeb.webChromeClient = EmbedNavigationWebChromeClient(
                            session = navigationSession,
                            guard = navigationGuard,
                            onFullscreenChanged = { fullscreen ->
                                currentOnFullscreenChanged(fullscreen)
                            },
                        )
                        DiagnosticsLog.event(
                            "EmbedWebView loadUrl after blank host=${activeUrl.hostOrNone()} " +
                                "generation=${navigationSession.generation} " +
                                "headers=${headers.keys.joinToString()}",
                        )
                        if (shouldResumeEmbedForLifecycleState(lifecycleOwner?.lifecycle?.currentState)) {
                            targetWeb.onResume()
                        }
                        if (usesAllAnimeIframeShell) {
                            // OK.ru returns a blank document when opened as a top-level WebView
                            // page. AllAnime supplies the iframe navigation metadata and referer.
                            targetWeb.loadDataWithBaseURL(
                                activeReferer,
                                allAnimeIframeShell(activeUrl),
                                "text/html",
                                "UTF-8",
                                null,
                            )
                        } else {
                            targetWeb.loadUrl(activeUrl, headers)
                        }
                    }

                    // A reused WebView does not synchronously remove its old media document when
                    // loadUrl(new) is called. Silence it first, commit a blank document, and only
                    // then install the next episode. This prevents two Chromium media surfaces (or
                    // their audio) from coexisting during Next/SUB-DUB/source transitions.
                    web.webChromeClient = WebChromeClient()
                    web.webViewClient = EmbedTeardownWebViewClient(
                        session = navigationSession,
                        guard = navigationGuard,
                        transitionGate = documentTransitionGate,
                        onBlankCommitted = { blankView ->
                            loadRequestedDocument(blankView)
                        },
                        onRenderProcessGoneAccepted = handleRendererGone,
                    )
                    DiagnosticsLog.event(
                        "EmbedWebView teardown before navigation generation=${navigationSession.generation}",
                    )
                    beginEmbedDocumentTeardown(
                        webView = web,
                        session = navigationSession,
                        guard = navigationGuard,
                        transitionGate = documentTransitionGate,
                    )
                }
            },
            onRelease = { view ->
                val web = view as? WebView ?: return@AndroidView
                (web.tag as? EmbedNavigationSession)?.let { releasedSession ->
                    documentTransitionGate.invalidate(releasedSession)
                    navigationGuard.invalidate(releasedSession)
                }
                if (webView === web) webView = null
                val releasedUrl = runCatching { web.url }.getOrNull()
                DiagnosticsLog.event(
                    "EmbedWebView release url=${releasedUrl ?: "none"} size=${web.width}x${web.height}",
                )
                stopWebPlayback(web)
                runCatching { web.removeJavascriptInterface("AniliProgress") }
                runCatching { web.clearHistory() }
                runCatching { web.removeAllViews() }
                runCatching { web.webChromeClient = null }
                runCatching { web.webViewClient = WebViewClient() }
                runCatching { web.destroy() }
            },
            )
        }

        loadError?.let { message ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "This server's page failed to load",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "$message — try another server below.",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // Swallows every touch so the page never sees one: web players only raise their control
        // chrome on interaction, so starving them of taps keeps the provider UI hidden and lets
        // our overlay be the only controls. Also neuters tap-hijack ads, which need a real click.
        // A vertical drag down the left edge scrubs brightness, down the right edge volume.
        if (touchControlsActive) PlayerGestureControls(
            onTap = {
                touchControlsVisible = !touchControlsVisible
                touchControlsInteraction++
            },
            onDoubleTap = { isRightHalf ->
                if (isRightHalf) {
                    requestSeek(positionMs + 10_000L, null)
                } else {
                    val target = (positionMs - 10_000L).coerceAtLeast(0L)
                    requestSeek(target, null)
                }
            },
            onHoldSpeed = { active ->
                if (active) {
                    preHoldSpeed = playbackSpeed
                    playbackSpeed = 2f
                } else {
                    playbackSpeed = preHoldSpeed
                }
            },
        )

        // For cross-origin players we cannot identify or command the content video. Never synthesize
        // a blind tap at an arbitrary coordinate: Kiwi-like pages may place a second player or ad
        // there. The first tap only removes our overlay; the viewer's next real tap reaches the
        // provider's own controls unchanged.
        if (fallbackControlsActive && !providerControlsMode) Box(
            Modifier
                .fillMaxSize()
                .pointerInput(navigationSession.generation) {
                    detectTapGestures {
                        if (fallbackControlsVisible) {
                            fallbackControlsVisible = false
                            providerControlsMode = true
                            webView?.requestFocus()
                        } else {
                            fallbackControlsVisible = true
                            fallbackInteraction++
                        }
                    }
                },
        )

        if (touchControlsActive && touchControlsVisible) {
            EmbedTouchControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = webIsPlaying,
                hasPrevious = hasPreviousEpisode && currentOnPreviousEpisode != null,
                hasNext = hasNextEpisode && currentOnNextEpisode != null,
                onPrevious = { currentOnPreviousEpisode?.invoke(playbackKey) },
                onRewind = {
                    requestSeek((positionMs - 10_000L).coerceAtLeast(0L), null)
                    touchControlsInteraction++
                },
                onPlayPause = {
                    DiagnosticsLog.event("EmbedWebView touch control playPause")
                    requestTogglePlayback()
                    touchControlsInteraction++
                },
                onForward = {
                    requestSeek(positionMs + 10_000L, null)
                    touchControlsInteraction++
                },
                onNext = { currentOnNextEpisode?.invoke(playbackKey) },
                onSeek = { targetMs ->
                    requestSeek(targetMs, null)
                    touchControlsInteraction++
                },
                onSettings = { settingsSheetVisible = true },
                seriesTitle = seriesTitle,
                episodeTitle = episodeTitle,
                isFullscreen = isFullscreen,
                onFullscreen = onToggleFullscreen,
                onInteract = { touchControlsInteraction++ },
            )
        }

        if (settingsSheetVisible) {
            PlayerSettingsSheet(
                onDismiss = { settingsSheetVisible = false },
                autoplay = autoplay,
                onAutoplayChange = SettingsStore::setAutoplay,
                canAutomatePlayback = canAutomatePlayback,
                speed = if (canControlPlayback) playbackSpeed else null,
                onSpeedChange = { playbackSpeed = it },
                qualityOptions = embedQualityStreams.mapNotNull { option ->
                    val height = option.height ?: declaredVideoHeight(option.quality) ?: return@mapNotNull null
                    PlayerQualityOption(
                        label = "${height}p",
                        selected = option.url == activeUrl,
                        onSelect = {
                            if (option.url != activeUrl) {
                                // Revoke A synchronously in the click that requests B; the WebView
                                // itself is replaced later by AndroidView.update.
                                navigationGuard.invalidate(navigationSession)
                                pendingSeekMs = positionMs
                                activeUrl = option.url
                            }
                        },
                    )
                },
                onCaptionAppearance = if (canControlPlayback) {
                    { captionAppearanceVisible = true }
                } else {
                    null
                },
                autoSkip = autoSkipIntroOutro.takeIf { canAutomatePlayback },
                onAutoSkipChange = SettingsStore::setAutoSkipIntroOutro,
            )
        }

        // Cross-origin providers keep their own seek/play surface. Present only the controls this
        // app can honour and an explicit hand-off to the provider; never imply that an inaccessible
        // media element can be paused or scrubbed by AniLili+.
        if (fallbackControlsActive && fallbackControlsVisible) EmbedFallbackControls(
            seriesTitle = seriesTitle,
            episodeTitle = episodeTitle,
            hasPrevious = hasPreviousEpisode && currentOnPreviousEpisode != null,
            hasNext = hasNextEpisode && currentOnNextEpisode != null,
            onPrevious = { currentOnPreviousEpisode?.invoke(playbackKey) },
            onUseProviderControls = {
                DiagnosticsLog.event("EmbedWebView exposing cross-origin provider controls")
                fallbackControlsVisible = false
                providerControlsMode = true
                webView?.requestFocus()
                fallbackInteraction++
            },
            onNext = { currentOnNextEpisode?.invoke(playbackKey) },
            onSettings = {
                settingsSheetVisible = true
                fallbackInteraction++
            },
            isFullscreen = isFullscreen,
            onFullscreen = onToggleFullscreen?.let { toggle ->
                {
                    toggle()
                    fallbackInteraction++
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )

        if (playbackMode.controlsPlayback && device.isTv && focusPlayerOnStart && tvControlsVisible) {
            TvPlayerControls(
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = webIsPlaying,
                isMuted = (if (webPlaybackAvailable) webVolume else deviceVolume) <= 0.001f,
                hasPrevious = hasPreviousEpisode && currentOnPreviousEpisode != null,
                hasNext = hasNextEpisode && currentOnNextEpisode != null,
                playPauseFocusRequester = tvPlayPauseFocus,
                onPrevious = { currentOnPreviousEpisode?.invoke(playbackKey) },
                onRewind = {
                    requestSeek((positionMs - 10_000L).coerceAtLeast(0L), null)
                },
                onPlayPause = {
                    DiagnosticsLog.event("EmbedWebView TV control playPause")
                    requestTogglePlayback()
                },
                onForward = {
                    requestSeek(positionMs + 10_000L, null)
                },
                onNext = { currentOnNextEpisode?.invoke(playbackKey) },
                onVolumeDown = {
                    DiagnosticsLog.event("EmbedWebView TV control volumeDown available=$webPlaybackAvailable")
                    if (webPlaybackAvailable) {
                        adjustWebVolume(webView, -0.1f, navigationSession, navigationGuard) { volume ->
                            webVolume = volume
                            if (volume > 0f) lastAudibleVolume = volume
                        }
                    } else {
                        deviceVolume = (readDeviceVolume(embedAudioManager) - 0.1f).coerceIn(0f, 1f)
                        applyDeviceVolume(embedAudioManager, deviceVolume)
                        if (deviceVolume > 0f) lastAudibleVolume = deviceVolume
                    }
                },
                onToggleMute = {
                    DiagnosticsLog.event("EmbedWebView TV control toggleMute available=$webPlaybackAvailable")
                    if (webPlaybackAvailable) {
                        if (webVolume > 0.001f) lastAudibleVolume = webVolume
                        val target = if (webVolume > 0.001f) 0f else lastAudibleVolume.coerceAtLeast(0.1f)
                        setWebVolume(webView, target, navigationSession, navigationGuard) { webVolume = it }
                    } else {
                        val current = readDeviceVolume(embedAudioManager)
                        if (current > 0.001f) lastAudibleVolume = current
                        deviceVolume = if (current > 0.001f) 0f else lastAudibleVolume.coerceAtLeast(0.1f)
                        applyDeviceVolume(embedAudioManager, deviceVolume)
                    }
                },
                onVolumeUp = {
                    DiagnosticsLog.event("EmbedWebView TV control volumeUp available=$webPlaybackAvailable")
                    if (webPlaybackAvailable) {
                        adjustWebVolume(webView, 0.1f, navigationSession, navigationGuard) { volume ->
                            webVolume = volume
                            lastAudibleVolume = volume
                        }
                    } else {
                        deviceVolume = (readDeviceVolume(embedAudioManager) + 0.1f).coerceIn(0f, 1f)
                        applyDeviceVolume(embedAudioManager, deviceVolume)
                        lastAudibleVolume = deviceVolume
                    }
                },
                onSettings = {
                    // The full settings panel (quality, speed, captions, autoplay, auto-skip,
                    // volume) with the TV side-panel presentation — the old quality-or-speed
                    // dialog left most settings unreachable from a remote.
                    DiagnosticsLog.event("EmbedWebView TV control settings")
                    settingsSheetVisible = true
                },
                onFullscreen = onToggleFullscreen,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        val action = if (!canAutomatePlayback) {
            null
        } else {
            skipPlan.actionAt(
                positionMs = positionMs,
                hasNextEpisode = currentHasNextEpisode && currentOnNextEpisode != null,
            )?.let { planned ->
                planned.label to {
                    if (planned.advanceToNextEpisode) {
                        currentOnNextEpisode?.invoke(playbackKey)
                    } else {
                        planned.seekTargetMs?.let { requestSeek(it, null) }
                    }
                    Unit
                }
            }
        }
        action?.let { (label, onClick) ->
            WebSkipButton(
                label = label,
                onClick = onClick,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

private fun String.requiresAllAnimeIframeShell(referer: String?): Boolean {
    val host = runCatching { Uri.parse(this).host }.getOrNull()?.lowercase()?.removePrefix("www.")
    val refererHost = runCatching { Uri.parse(referer).host }.getOrNull()?.lowercase()?.removePrefix("www.")
    return host == "ok.ru" && refererHost == "allanime.day"
}

private fun allAnimeIframeShell(url: String): String {
    val escaped = url
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head><body style="margin:0;background:#000;overflow:hidden"><iframe src="$escaped" allow="autoplay; fullscreen; encrypted-media; picture-in-picture" allowfullscreen referrerpolicy="no-referrer-when-downgrade" style="position:fixed;inset:0;width:100%;height:100%;border:0"></iframe></body></html>"""
}

@Composable
private fun WebSkipButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(3.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.55f)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Black.copy(alpha = 0.5f),
            contentColor = Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = modifier
            .padding(start = 24.dp, bottom = 24.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Installs progress/end reporting with two independent credentials: an unguessable bridge
 * capability and the current navigation generation. The former blocks third-party frames from
 * forging native callbacks; the latter makes callbacks from a replaced document harmless.
 */
private fun authenticatedProgressPollJs(
    navigationGeneration: Long,
    capabilityToken: String,
): String = """
    (function() {
      var navigationToken = '$navigationGeneration';
      var capabilityToken = '$capabilityToken';
      if (window.__aniliNavigationToken && window.__aniliNavigationToken !== navigationToken) return;
      window.__aniliNavigationToken = navigationToken;
      window.__aniliNavigationRevoked = false;
      if (window.__aniliProgressHookedFor === navigationToken) return;
      window.__aniliProgressHookedFor = navigationToken;
      ${embedContentVideoSelectorJs()}
      var observedVideo = null;
      var observedMediaKey = null;
      var observedPlayingSamples = 0;
      var endedHandler = null;
      var endedReportedMediaKey = null;
      function mediaKey(video) {
        return __aniliMediaIdentity(video);
      }
      function reportEnded(video) {
        var key = mediaKey(video);
        if (video !== observedVideo || key !== observedMediaKey || endedReportedMediaKey === key) return;
        if (!isFinite(video.duration) || video.duration <= 0 || video.currentTime < 0) return;
        endedReportedMediaKey = key;
        try {
          AniliProgress.onEnded(
            capabilityToken,
            navigationToken,
            video.currentTime,
            video.duration,
            observedPlayingSamples
          );
        } catch (e) { /* bridge detached */ }
      }
      function observeVideo(video) {
        var key = mediaKey(video);
        if (video === observedVideo && key === observedMediaKey) return;
        if (video !== observedVideo) {
          try {
            if (observedVideo && endedHandler) observedVideo.removeEventListener('ended', endedHandler);
          } catch (e) { /* replaced frame */ }
          endedHandler = function() { reportEnded(video); };
          video.addEventListener('ended', endedHandler);
        }
        observedVideo = video;
        observedMediaKey = key;
        observedPlayingSamples = 0;
        endedReportedMediaKey = null;
      }
      var timer = setInterval(function() {
        if (window.__aniliNavigationRevoked || window.__aniliNavigationToken !== navigationToken) {
          clearInterval(timer);
          return;
        }
        try {
          var v = findContentVideo();
          __aniliPauseCompetingMedia(v);
          if (v) {
            observeVideo(v);
          }
          if (v && !window.__aniliVideoReported) {
            window.__aniliVideoReported = true;
            AniliProgress.onVideoAvailable(capabilityToken, navigationToken);
          }
          if (v && isFinite(v.duration) && v.duration > 0 && v.currentTime >= 0) {
            if (!v.paused) observedPlayingSamples++;
            AniliProgress.onTick(
              capabilityToken,
              navigationToken,
              v.currentTime,
              v.duration,
              !v.paused,
              v.muted,
              v.volume,
              mediaKey(v)
            );
            if (v.ended) reportEnded(v);
          }
        } catch (e) { /* bridge detached */ }
      }, 1000);
    })();
""".trimIndent()

/** Capability-scoped command; same-origin iframe limitations are unchanged. */
private fun SET_PLAYBACK_SPEED_JS(speed: Float, navigationGeneration: Long): String = """
    (function() {
      ${embedNavigationJsGuard(navigationGeneration)}
      ${embedContentVideoSelectorJs()}
      try {
        var v = findContentVideo();
        if (!v) return false;
        v.defaultPlaybackRate = $speed;
        v.playbackRate = $speed;
        return Math.abs(v.playbackRate - $speed) < 0.01;
      } catch (e) {
        return false;
      }
    })();
""".trimIndent()

private const val CAPTION_STYLE_ELEMENT_ID = "anili-caption-style"

/**
 * Restyles captions in the main document and any same-origin iframe. Idempotent: re-running with a
 * new style rewrites the same `<style>` element instead of stacking duplicates.
 */
private fun CAPTION_STYLE_JS(style: CaptionStyle, navigationGeneration: Long): String = """
    (function() {
      ${embedNavigationJsGuard(navigationGeneration)}
      var css = ${captionCss(style).toJsStringLiteral()};
      function apply(doc) {
        if (!doc) return;
        var el = doc.getElementById('$CAPTION_STYLE_ELEMENT_ID');
        if (!el) {
          el = doc.createElement('style');
          el.id = '$CAPTION_STYLE_ELEMENT_ID';
          (doc.head || doc.documentElement).appendChild(el);
        }
        el.textContent = css;
      }
      try {
        apply(document);
        var frames = document.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          try { apply(frames[i].contentDocument); } catch (e) { /* cross-origin */ }
        }
      } catch (e) { /* ignored */ }
    })();
""".trimIndent()

/**
 * `::cue` covers Chromium's own WebVTT rendering; the class selectors cover the embed players that
 * draw captions as ordinary DOM instead. Ours is appended last, so at equal specificity it wins.
 */
internal fun captionCss(style: CaptionStyle): String {
    val background = style.backgroundCssRgba()
    // `background` first so the shorthand can't reset the colour we set right after it.
    val declarations = "background: $background !important; " +
        "background-color: $background !important; " +
        "color: ${style.textCssHex()} !important; " +
        "text-shadow: ${style.edgeStyle.toCssTextShadow()} !important; " +
        "font-size: ${style.textScalePercent}% !important;"
    return "::cue { $declarations }\n$DOM_CAPTION_SELECTORS { $declarations }"
}

private const val DOM_CAPTION_SELECTORS =
    ".plyr__caption, .vjs-text-track-cue > div, .jw-text-track-cue, .art-subtitle"

private const val APP_PROVIDER_CONTROLS_STYLE_ID = "anili-provider-controls-style"

private const val PROVIDER_CONTROL_SELECTORS =
    ".plyr__controls, .vjs-control-bar, .jw-controlbar, .art-controls, " +
        ".dplayer-controller, .media-control, .mejs__controls"

/** Hides provider chrome only in documents where AniLili can control the selected content video. */
internal fun providerControlsVisibilityJs(
    hidden: Boolean,
    navigationGeneration: Long,
): String {
    val css = if (hidden) {
        "$PROVIDER_CONTROL_SELECTORS { opacity: 0 !important; visibility: hidden !important; " +
            "pointer-events: none !important; }"
    } else {
        ""
    }
    return """
        (function() {
          ${embedNavigationJsGuard(navigationGeneration)}
          ${embedContentVideoSelectorJs()}
          var css = ${css.toJsStringLiteral()};
          function apply(doc) {
            if (!doc) return;
            var style = doc.getElementById('$APP_PROVIDER_CONTROLS_STYLE_ID');
            if (!css) {
              if (style && style.parentNode) style.parentNode.removeChild(style);
            } else {
              if (!style) {
                style = doc.createElement('style');
                style.id = '$APP_PROVIDER_CONTROLS_STYLE_ID';
                (doc.head || doc.documentElement).appendChild(style);
              }
              style.textContent = css;
            }
            var frames = doc.querySelectorAll('iframe');
            for (var i = 0; i < frames.length; i++) {
              try { apply(frames[i].contentDocument); } catch (e) { /* cross-origin */ }
            }
          }
          apply(document);
          var video = findContentVideo();
          if (video) {
            if (css) {
              if (video.__aniliHadNativeControls === undefined) {
                video.__aniliHadNativeControls = video.hasAttribute('controls');
              }
              video.controls = false;
              video.removeAttribute('controls');
            } else if (video.__aniliHadNativeControls !== undefined) {
              if (video.__aniliHadNativeControls) video.setAttribute('controls', '');
              video.controls = !!video.__aniliHadNativeControls;
              delete video.__aniliHadNativeControls;
            }
          }
        })();
    """.trimIndent()
}

private fun CaptionEdgeStyle.toCssTextShadow(): String = when (this) {
    CaptionEdgeStyle.NONE -> "none"
    CaptionEdgeStyle.OUTLINE ->
        "-1px -1px 1px #000, 1px -1px 1px #000, -1px 1px 1px #000, 1px 1px 1px #000"
    CaptionEdgeStyle.DROP_SHADOW -> "2px 2px 3px rgba(0, 0, 0, 0.9)"
}

private fun String.toJsStringLiteral(): String =
    "'" + replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"

internal fun togglePlaybackCommandJs(
    navigationGeneration: Long,
    capabilityToken: String,
    commandId: Long,
    expectedMediaIdentity: String? = null,
): String = """
    (function() {
      ${embedNavigationJsGuard(navigationGeneration)}
      ${embedContentVideoSelectorJs()}
      var expectedMediaIdentity = ${expectedMediaIdentity?.toJsStringLiteral() ?: "null"};
      var reported = false;
      function report(success, video) {
        if (reported) return;
        reported = true;
        var position = video && isFinite(video.currentTime) ? video.currentTime : 0;
        var playing = !!video && !video.paused && !video.ended;
        var mediaIdentity = __aniliMediaIdentity(video);
        success = success && !!video && findContentVideo() === video &&
          (!expectedMediaIdentity || mediaIdentity === expectedMediaIdentity);
        try {
          AniliProgress.onCommandResult(
            '$capabilityToken', '$navigationGeneration', '$commandId', success, position, playing,
            mediaIdentity
          );
        } catch (e) { /* bridge detached */ }
      }
      try {
        var video = findContentVideo();
        if (!video) { report(false, null); return; }
        if (expectedMediaIdentity && __aniliMediaIdentity(video) !== expectedMediaIdentity) {
          report(false, video); return;
        }
        if (video.paused || video.ended) {
          __aniliPauseCompetingMedia(video);
          var playResult = video.play();
          if (playResult && typeof playResult.then === 'function') {
            playResult.then(function() { report(!video.paused, video); })
              .catch(function() { report(false, video); });
          } else {
            setTimeout(function() { report(!video.paused, video); }, 0);
          }
        } else {
          __aniliPauseAllMedia();
          setTimeout(function() { report(video.paused, video); }, 0);
        }
        setTimeout(function() { report(false, video); }, 1200);
      } catch (e) { report(false, null); }
    })();
""".trimIndent()

internal fun seekVideoCommandJs(
    targetSec: Double,
    navigationGeneration: Long,
    capabilityToken: String,
    commandId: Long,
    expectedMediaIdentity: String? = null,
): String = """
    (function() {
      ${embedNavigationJsGuard(navigationGeneration)}
      ${embedContentVideoSelectorJs()}
      var expectedMediaIdentity = ${expectedMediaIdentity?.toJsStringLiteral() ?: "null"};
      var reported = false;
      function report(success, video) {
        if (reported) return;
        reported = true;
        var position = video && isFinite(video.currentTime) ? video.currentTime : 0;
        var playing = !!video && !video.paused && !video.ended;
        var mediaIdentity = __aniliMediaIdentity(video);
        success = success && !!video && findContentVideo() === video &&
          (!expectedMediaIdentity || mediaIdentity === expectedMediaIdentity);
        try {
          AniliProgress.onCommandResult(
            '$capabilityToken', '$navigationGeneration', '$commandId', success, position, playing,
            mediaIdentity
          );
        } catch (e) { /* bridge detached */ }
      }
      try {
        var video = findContentVideo();
        if (!video) { report(false, null); return; }
        if (expectedMediaIdentity && __aniliMediaIdentity(video) !== expectedMediaIdentity) {
          report(false, video); return;
        }
        var target = $targetSec;
        var bounded = isFinite(video.duration) && video.duration > 0
          ? Math.min(Math.max(0, target), video.duration)
          : Math.max(0, target);
        function confirmSeek() {
          report(Math.abs(video.currentTime - bounded) <= 1.5, video);
        }
        video.addEventListener('seeked', confirmSeek, { once: true });
        video.currentTime = bounded;
        setTimeout(confirmSeek, 150);
        setTimeout(function() { report(false, video); }, 1200);
      } catch (e) { report(false, null); }
    })();
""".trimIndent()

private fun dispatchWebCommand(
    webView: WebView?,
    session: EmbedNavigationSession,
    guard: EmbedNavigationGuard,
    coordinator: EmbedCommandCoordinator,
    callbacks: MutableMap<Long, (EmbedCommandResolution) -> Unit>,
    handler: Handler,
    kind: EmbedCommandKind,
    mediaIdentity: String?,
    script: (Long) -> String,
    onResolved: (EmbedCommandResolution) -> Unit,
): Boolean {
    val web = webView ?: return false
    if (!guard.isCurrent(session)) return false
    val command = coordinator.issue(
        navigationGeneration = session.generation,
        kind = kind,
        nowMs = SystemClock.uptimeMillis(),
        mediaIdentity = mediaIdentity,
    )
    callbacks[command.id] = onResolved
    val dispatched = runCatching {
        web.evaluateJavascript(script(command.id), null)
    }.isSuccess
    if (!dispatched) {
        coordinator.cancel(command.id)
        callbacks.remove(command.id)
        return false
    }
    handler.postDelayed(
        {
            val resolution = coordinator.timeout(command.id, SystemClock.uptimeMillis())
            val callback = callbacks.remove(command.id)
            if (resolution !is EmbedCommandResolution.Ignored) callback?.invoke(resolution)
        },
        EMBED_COMMAND_TIMEOUT_MS,
    )
    return true
}

private fun adjustWebVolume(
    webView: WebView?,
    delta: Float,
    session: EmbedNavigationSession,
    guard: EmbedNavigationGuard,
    onChanged: (Float) -> Unit,
) {
    val web = webView ?: return
    if (!guard.isCurrent(session)) return
    runCatching {
        web.evaluateJavascript(
            WEB_VOLUME_JS(
                delta = delta,
                absolute = null,
                navigationGeneration = session.generation,
            ),
        ) { result ->
            if (guard.isCurrent(session)) {
                result.toFloatOrNull()?.coerceIn(0f, 1f)?.let(onChanged)
            }
        }
    }
}

private fun setWebVolume(
    webView: WebView?,
    volume: Float,
    session: EmbedNavigationSession,
    guard: EmbedNavigationGuard,
    onChanged: (Float) -> Unit,
) {
    val web = webView ?: return
    if (!guard.isCurrent(session)) return
    runCatching {
        web.evaluateJavascript(
            WEB_VOLUME_JS(
                delta = null,
                absolute = volume,
                navigationGeneration = session.generation,
            ),
        ) { result ->
            if (guard.isCurrent(session)) {
                result.toFloatOrNull()?.coerceIn(0f, 1f)?.let(onChanged)
            }
        }
    }
}

private fun WEB_VOLUME_JS(delta: Float?, absolute: Float?, navigationGeneration: Long): String {
    val targetExpression = absolute?.coerceIn(0f, 1f)?.toString()
        ?: "Math.max(0, Math.min(1, v.volume + ${delta ?: 0f}))"
    return """
        (function() {
          ${embedNavigationJsGuard(navigationGeneration)}
          ${embedContentVideoSelectorJs()}
          try {
            var v = findContentVideo();
            if (!v) return -1;
            v.volume = $targetExpression;
            v.muted = v.volume <= 0;
            return v.muted ? 0 : v.volume;
          } catch (e) {
            return -1;
          }
        })();
    """.trimIndent()
}

private const val AUTO_SKIP_RETRY_MS = 500L

private fun readDeviceVolume(audioManager: android.media.AudioManager?): Float {
    audioManager ?: return 1f
    val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    if (max <= 0) return 1f
    return audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun applyDeviceVolume(audioManager: android.media.AudioManager?, value: Float) {
    audioManager ?: return
    val max = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
    if (max <= 0) return
    audioManager.setStreamVolume(
        android.media.AudioManager.STREAM_MUSIC,
        (value * max).toInt().coerceIn(0, max),
        0,
    )
}

/**
 * Revokes and silences the committed document before asking Chromium to replace it with blank.
 * The callback is capability-checked because a faster Next/source request may supersede this one
 * while JavaScript from the outgoing page is still completing.
 */
private fun beginEmbedDocumentTeardown(
    webView: WebView,
    session: EmbedNavigationSession,
    guard: EmbedNavigationGuard,
    transitionGate: EmbedDocumentTransitionGate,
) {
    var blankRequested = false
    fun requestBlankIfCurrent() {
        if (
            blankRequested ||
            !guard.isCurrent(session) ||
            webView.tag !== session ||
            !transitionGate.markBlankRequested(session)
        ) {
            return
        }
        blankRequested = true
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl(EMBED_BLANK_DOCUMENT_URL) }
    }

    val teardownScript = "$REVOKE_EMBED_NAVIGATION_JS\n$SILENCE_EMBED_MEDIA_JS"
    // onPause is instance-local and immediately suspends cross-origin media that JavaScript cannot
    // reach. The target document is resumed only after the blank commit.
    runCatching { webView.onPause() }
    val dispatched = runCatching {
        webView.evaluateJavascript(teardownScript) { requestBlankIfCurrent() }
    }.isSuccess
    if (!dispatched) requestBlankIfCurrent()
    webView.postDelayed({ requestBlankIfCurrent() }, EMBED_TEARDOWN_JS_MAX_WAIT_MS)
}

private fun stopWebPlayback(webView: WebView) {
    DiagnosticsLog.event("EmbedWebView stop playback url=${webView.url ?: "none"}")
    // pauseTimers() is process-wide and can freeze OAuth and hidden resolver WebViews.
    runCatching { webView.onPause() }
    var blankRequested = false
    fun finishStop() {
        if (blankRequested) return
        blankRequested = true
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl(EMBED_BLANK_DOCUMENT_URL) }
    }
    val dispatched = runCatching {
        webView.evaluateJavascript(SILENCE_EMBED_MEDIA_JS) { finishStop() }
    }.isSuccess
    if (!dispatched) finishStop()
    webView.postDelayed({ finishStop() }, EMBED_TEARDOWN_JS_MAX_WAIT_MS)
}

/** A paused Activity remains STARTED; WebView media may resume only with a truly resumed owner. */
internal fun shouldResumeEmbedForLifecycleState(state: Lifecycle.State?): Boolean =
    state == null || state.isAtLeast(Lifecycle.State.RESUMED)

internal fun shouldResumeEmbedForLifecycleEvent(event: Lifecycle.Event): Boolean =
    event == Lifecycle.Event.ON_RESUME

/** Reversible lifecycle pause: never changes mute or volume state. */
internal fun pauseEmbedMediaForLifecycleJs(): String = """
    (function() {
      function pauseMedia(root) {
        var media = root.querySelectorAll('video,audio');
        for (var i = 0; i < media.length; i++) {
          try { media[i].pause(); } catch (e) { /* detached media */ }
        }
        var frames = root.querySelectorAll('iframe');
        for (var j = 0; j < frames.length; j++) {
          try {
            var child = frames[j].contentDocument;
            if (child) pauseMedia(child);
          } catch (e) { /* cross-origin */ }
        }
      }
      try { pauseMedia(document); } catch (e) { /* ignored */ }
    })();
""".trimIndent()

/** Terminal teardown: pause and silence every reachable media element before blanking. */
internal fun silenceEmbedMediaForTeardownJs(): String = """
    (function() {
      function silenceMedia(root) {
        var media = root.querySelectorAll('video,audio');
        for (var i = 0; i < media.length; i++) {
          try {
            media[i].pause();
            media[i].defaultMuted = true;
            media[i].muted = true;
            media[i].volume = 0;
          } catch (e) { /* detached media */ }
        }
        var frames = root.querySelectorAll('iframe');
        for (var j = 0; j < frames.length; j++) {
          try {
            var child = frames[j].contentDocument;
            if (child) silenceMedia(child);
          } catch (e) { /* cross-origin */ }
        }
      }
      try { silenceMedia(document); } catch (e) { /* ignored */ }
    })();
""".trimIndent()

private val PAUSE_EMBED_MEDIA_JS = pauseEmbedMediaForLifecycleJs()
private val SILENCE_EMBED_MEDIA_JS = silenceEmbedMediaForTeardownJs()

private const val EMBED_TEARDOWN_JS_MAX_WAIT_MS = 250L

private tailrec fun Context.findLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.findLifecycleOwner()
    else -> null
}

private fun isInSkipWindow(positionMs: Long, startMs: Long?, endMs: Long?): Boolean {
    val start = startMs ?: 0L
    val end = endMs ?: return false
    return end > start && positionMs in start until end
}

private fun String?.hostOrNone(): String =
    this?.let { runCatching { Uri.parse(it).host }.getOrNull() } ?: "none"

/** Client used only while the outgoing episode is being replaced by `about:blank`. */
private class EmbedTeardownWebViewClient(
    private val session: EmbedNavigationSession,
    private val guard: EmbedNavigationGuard,
    private val transitionGate: EmbedDocumentTransitionGate,
    private val onBlankCommitted: (WebView) -> Unit,
    private val onRenderProcessGoneAccepted: (WebView?, RenderProcessGoneDetail?) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        if (request?.isForMainFrame != true) return false
        return request.url?.toString() != EMBED_BLANK_DOCUMENT_URL
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        val blank = view ?: return
        if (
            guard.isCurrent(session) &&
            blank.tag === session &&
            transitionGate.acceptBlankFinished(session, url, blank.url)
        ) {
            DiagnosticsLog.event(
                "EmbedWebView blank committed generation=${session.generation}",
            )
            onBlankCommitted(blank)
        } else {
            DiagnosticsLog.event(
                "EmbedWebView ignored stale/non-blank teardown finish " +
                    "generation=${session.generation} callback=${url.hostOrNone()}",
            )
        }
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        if (guard.isCurrent(session)) {
            transitionGate.invalidate(session)
            onRenderProcessGoneAccepted(view, detail)
        } else {
            DiagnosticsLog.event(
                "EmbedWebView ignored stale teardown renderer callback generation=${session.generation}",
            )
        }
        return true
    }
}

private class EmbedNavigationWebChromeClient(
    private val session: EmbedNavigationSession,
    private val guard: EmbedNavigationGuard,
    private val onFullscreenChanged: (Boolean) -> Unit,
) : WebChromeClient() {
    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?,
    ): Boolean {
        DiagnosticsLog.event(
            "EmbedWebView blocked popup generation=${session.generation} " +
                "isDialog=$isDialog userGesture=$isUserGesture",
        )
        return false
    }

    // Deny Chromium's detachable custom view and expand the app-owned WebView instead. The
    // capability check prevents a delayed fullscreen callback from A from rotating episode B.
    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        callback?.onCustomViewHidden()
        if (guard.isCurrent(session)) {
            DiagnosticsLog.event("EmbedWebView custom fullscreen requested generation=${session.generation}")
            onFullscreenChanged(true)
        } else {
            DiagnosticsLog.event("EmbedWebView ignored stale custom fullscreen generation=${session.generation}")
        }
    }

    override fun onHideCustomView() {
        if (guard.isCurrent(session)) {
            DiagnosticsLog.event("EmbedWebView custom fullscreen hidden generation=${session.generation}")
            onFullscreenChanged(false)
        } else {
            DiagnosticsLog.event("EmbedWebView ignored stale fullscreen hide generation=${session.generation}")
        }
    }
}

private class EmbedNavigationWebViewClient(
    private val session: EmbedNavigationSession,
    private val guard: EmbedNavigationGuard,
    private val onPageStartedAccepted: (String) -> Unit,
    private val onPageFinishedAccepted: (WebView, String) -> Unit,
    private val onMainFrameErrorAccepted: (String) -> Unit,
    private val onRenderProcessGoneAccepted: (WebView?, RenderProcessGoneDetail?) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val target = request?.url ?: return true
        if (!request.isForMainFrame) return false
        val allowed = guard.allowsMainFrameNavigation(session, target.toString())
        if (!allowed) {
            DiagnosticsLog.event(
                "EmbedWebView blocked main-frame nav generation=${session.generation} " +
                    "targetHost=${target.host.orEmpty()}",
            )
        }
        return !allowed
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        if (guard.acceptPageStarted(session, url, view?.url)) {
            onPageStartedAccepted(requireNotNull(url))
        } else {
            DiagnosticsLog.event(
                "EmbedWebView ignored page started generation=${session.generation} " +
                    "callback=${url.hostOrNone()} current=${view?.url.hostOrNone()}",
            )
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        if (guard.acceptPageFinished(session, url, view?.url) && view != null && url != null) {
            onPageFinishedAccepted(view, url)
        } else {
            DiagnosticsLog.event(
                "EmbedWebView ignored page finished generation=${session.generation} " +
                    "callback=${url.hostOrNone()} current=${view?.url.hostOrNone()}",
            )
        }
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (request?.isForMainFrame != true) return
        if (!guard.acceptMainFrameError(session, request.url?.toString(), view?.url)) {
            DiagnosticsLog.event(
                "EmbedWebView ignored main-frame error generation=${session.generation} " +
                    "host=${request.url?.host ?: "unknown"}",
            )
            return
        }
        val message = error?.description?.toString() ?: "The server did not respond"
        DiagnosticsLog.event(
            "EmbedWebView main-frame error generation=${session.generation} " +
                "code=${error?.errorCode} description=${error?.description} " +
                "host=${request.url?.host ?: "unknown"}",
        )
        onMainFrameErrorAccepted(message)
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        if (request?.isForMainFrame != true) return
        if (!guard.acceptMainFrameError(session, request.url?.toString(), view?.url)) {
            DiagnosticsLog.event(
                "EmbedWebView ignored main-frame HTTP error generation=${session.generation} " +
                    "host=${request.url?.host ?: "unknown"}",
            )
            return
        }
        val message = "HTTP ${errorResponse?.statusCode ?: "error"} from the video server"
        DiagnosticsLog.event(
            "EmbedWebView main-frame HTTP error generation=${session.generation} " +
                "status=${errorResponse?.statusCode} reason=${errorResponse?.reasonPhrase} " +
                "host=${request.url?.host ?: "unknown"}",
        )
        onMainFrameErrorAccepted(message)
    }

    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
        if (guard.isCurrent(session)) {
            onRenderProcessGoneAccepted(view, detail)
        } else {
            DiagnosticsLog.event(
                "EmbedWebView ignored stale renderer callback generation=${session.generation}",
            )
        }
        return true
    }
}

internal class WebProgressBridge(
    private val expectedToken: String,
    private val onTickCallback: (String, Double, Double, Boolean, Boolean, Double, String) -> Unit,
    private val onVideoAvailableCallback: (String) -> Unit,
    private val onEndedCallback: (String, Double, Double, Int) -> Unit,
    private val onCommandResultCallback: (String, String, Boolean, Double, Boolean, String) -> Unit,
) {
    /** Compatibility constructor for callers that do not need navigation identity. */
    constructor(
        expectedToken: String,
        onTickCallback: (Double, Double, Boolean, Boolean, Double) -> Unit,
        onVideoAvailableCallback: () -> Unit,
    ) : this(
        expectedToken = expectedToken,
        onTickCallback = { _, positionSec, durationSec, isPlaying, muted, volume, _ ->
            onTickCallback(positionSec, durationSec, isPlaying, muted, volume)
        },
        onVideoAvailableCallback = { onVideoAvailableCallback() },
        onEndedCallback = { _, _, _, _ -> },
        onCommandResultCallback = { _, _, _, _, _, _ -> },
    )

    @JavascriptInterface
    fun onTick(
        capabilityToken: String?,
        navigationToken: String,
        positionSec: Double,
        durationSec: Double,
        isPlaying: Boolean,
        muted: Boolean,
        volume: Double,
        mediaIdentity: String,
    ) {
        if (capabilityToken != expectedToken) return
        onTickCallback(navigationToken, positionSec, durationSec, isPlaying, muted, volume, mediaIdentity)
    }

    fun onTick(
        capabilityToken: String?,
        positionSec: Double,
        durationSec: Double,
        isPlaying: Boolean,
        muted: Boolean,
        volume: Double,
    ) = onTick(
        capabilityToken,
        "",
        positionSec,
        durationSec,
        isPlaying,
        muted,
        volume,
        "",
    )

    @JavascriptInterface
    fun onVideoAvailable(capabilityToken: String?, navigationToken: String) {
        if (capabilityToken != expectedToken) return
        onVideoAvailableCallback(navigationToken)
    }

    fun onVideoAvailable(capabilityToken: String?) =
        onVideoAvailable(capabilityToken, "")

    @JavascriptInterface
    fun onEnded(
        capabilityToken: String?,
        navigationToken: String,
        positionSec: Double,
        durationSec: Double,
        observedPlayingSamples: Int,
    ) {
        if (capabilityToken != expectedToken) return
        onEndedCallback(navigationToken, positionSec, durationSec, observedPlayingSamples)
    }

    @JavascriptInterface
    fun onCommandResult(
        capabilityToken: String?,
        navigationToken: String,
        commandId: String,
        succeeded: Boolean,
        positionSec: Double,
        isPlaying: Boolean,
        mediaIdentity: String,
    ) {
        if (capabilityToken != expectedToken) return
        onCommandResultCallback(navigationToken, commandId, succeeded, positionSec, isPlaying, mediaIdentity)
    }
}
