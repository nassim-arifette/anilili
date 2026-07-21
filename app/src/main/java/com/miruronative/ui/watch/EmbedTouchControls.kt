package com.miruronative.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The embed player's touch controls: the shared [PlayerControlsScaffold] with settings and
 * fullscreen as its trailing icons. Drawn over the WebView when the injected JS reaches its `<video>`;
 * the touch-swallowing layer beneath keeps the provider's own chrome from ever appearing.
 */
@Composable
internal fun EmbedTouchControls(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    seriesTitle: String? = null,
    episodeTitle: String? = null,
    isFullscreen: Boolean = false,
    onFullscreen: (() -> Unit)? = null,
    onInteract: () -> Unit = {},
    primaryAction: PlayerChromeAction? = null,
) {
    PlayerControlsScaffold(
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        hasPrevious = hasPrevious,
        hasNext = hasNext,
        onPrevious = onPrevious,
        onRewind = onRewind,
        onPlayPause = onPlayPause,
        onForward = onForward,
        onNext = onNext,
        onSeek = onSeek,
        seriesTitle = seriesTitle,
        episodeTitle = episodeTitle,
        onExitFullscreen = if (isFullscreen) onFullscreen else null,
        onInteract = onInteract,
        primaryAction = primaryAction,
        modifier = modifier,
    ) {
        PlayerControlIconButton(
            "Settings",
            Icons.Default.Settings,
            onClick = {
                onSettings()
                onInteract()
            },
        )
        onFullscreen?.let { toggle ->
            PlayerControlIconButton(
                if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                onClick = {
                    toggle()
                    onInteract()
                },
            )
        }
    }
}

/**
 * Honest fallback chrome for cross-origin providers such as some Kiwi mirrors. We cannot seek or
 * pause media that Chromium keeps in an inaccessible frame, so this offers episode navigation and
 * a clearly labelled hand-off to the provider instead of drawing controls that would do nothing.
 */
@Composable
internal fun EmbedFallbackControls(
    seriesTitle: String?,
    episodeTitle: String?,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onUseProviderControls: () -> Unit,
    onNext: () -> Unit,
    onSettings: () -> Unit,
    isFullscreen: Boolean,
    onFullscreen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)),
                ),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        val showProviderLabel = maxWidth >= 520.dp
        Column {
            if (!seriesTitle.isNullOrBlank() || !episodeTitle.isNullOrBlank()) {
                Text(
                    seriesTitle ?: episodeTitle.orEmpty(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!episodeTitle.isNullOrBlank() && episodeTitle != seriesTitle) {
                    Text(
                        episodeTitle,
                        color = Color.White.copy(alpha = 0.68f),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayerControlIconButton(
                    "Previous episode",
                    Icons.Default.SkipPrevious,
                    enabled = hasPrevious,
                    onClick = onPrevious,
                )
                PlayerControlIconButton(
                    if (showProviderLabel) "Open provider controls" else "Use provider controls",
                    Icons.Default.TouchApp,
                    onClick = onUseProviderControls,
                )
                if (showProviderLabel) {
                    Text(
                        "PROVIDER CONTROLS",
                        color = Color.White.copy(alpha = 0.76f),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                PlayerControlIconButton(
                    "Next episode",
                    Icons.Default.SkipNext,
                    enabled = hasNext,
                    onClick = onNext,
                )
                Spacer(Modifier.weight(1f))
                PlayerControlIconButton("Settings", Icons.Default.Settings, onClick = onSettings)
                onFullscreen?.let { toggle ->
                    PlayerControlIconButton(
                        if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                        if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        onClick = toggle,
                    )
                }
            }
        }
    }
}
