package com.miruronative.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset

private val PlayerInk = Color(0xFFF4F1EA)
private val PlayerMutedInk = Color(0xFFC8C4BC)

/**
 * Shared touch chrome for native and scriptable embed playback.
 *
 * The inline player uses a compact composition while fullscreen landscape gets a more cinematic
 * title treatment and wider transport rhythm. Capabilities still come from each player: callers
 * provide only the trailing actions they can honour, so an embed never advertises native-only
 * audio, subtitle, Cast, or PiP controls.
 */
@Composable
internal fun PlayerControlsScaffold(
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    seriesTitle: String? = null,
    episodeTitle: String? = null,
    onExitFullscreen: (() -> Unit)? = null,
    onInteract: () -> Unit = {},
    bottomRightIcons: @Composable RowScope.() -> Unit = {},
) {
    // While the thumb is held, the slider follows the finger instead of the once-a-second tick.
    // Seeking only on release avoids repeatedly rebuilding provider/native buffers during a drag.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        val chromeLayout = playerChromeLayout(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value,
            fontScale = LocalDensity.current.fontScale,
        )
        val minimal = chromeLayout == PlayerChromeLayout.MINIMAL
        val compact = chromeLayout != PlayerChromeLayout.CINEMA
        val metrics = playerChromeVerticalMetrics(chromeLayout)
        val horizontalPadding = when (chromeLayout) {
            PlayerChromeLayout.MINIMAL -> 8.dp
            PlayerChromeLayout.COMPACT -> 12.dp
            PlayerChromeLayout.CINEMA -> 24.dp
        }

        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.56f),
                        0.30f to Color.Transparent,
                        0.58f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.88f),
                    ),
                ),
        )

        PlayerChromeHeader(
            seriesTitle = seriesTitle,
            episodeTitle = episodeTitle,
            compact = compact,
            showMetadata = !minimal,
            onExitFullscreen = onExitFullscreen,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = horizontalPadding, end = horizontalPadding, top = if (compact) 4.dp else 12.dp),
        )

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = metrics.transportOffsetDp.dp),
            horizontalArrangement = Arrangement.spacedBy(
                when (chromeLayout) {
                    PlayerChromeLayout.MINIMAL -> 2.dp
                    PlayerChromeLayout.COMPACT -> 4.dp
                    PlayerChromeLayout.CINEMA -> 18.dp
                },
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!minimal) {
                PlayerControlIconButton(
                    "Previous episode",
                    Icons.Default.SkipPrevious,
                    enabled = hasPrevious,
                    onClick = onPrevious,
                )
            }
            PlayerControlIconButton("Rewind 10 seconds", Icons.Default.Replay10, onClick = onRewind)
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier
                    .size(metrics.transportSizeDp.dp)
                    .clip(CircleShape)
                    .background(PlayerInk.copy(alpha = 0.14f))
                    .border(1.dp, PlayerInk.copy(alpha = 0.34f), CircleShape)
                    .semantics { contentDescription = if (isPlaying) "Pause" else "Play" },
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = PlayerInk,
                    modifier = Modifier.size(
                        when (chromeLayout) {
                            PlayerChromeLayout.MINIMAL -> 30.dp
                            PlayerChromeLayout.COMPACT -> 34.dp
                            PlayerChromeLayout.CINEMA -> 42.dp
                        },
                    ),
                )
            }
            PlayerControlIconButton("Forward 10 seconds", Icons.Default.Forward10, onClick = onForward)
            if (!minimal) {
                PlayerControlIconButton(
                    "Next episode",
                    Icons.Default.SkipNext,
                    enabled = hasNext,
                    onClick = onNext,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    horizontal = horizontalPadding,
                    vertical = when (chromeLayout) {
                        PlayerChromeLayout.MINIMAL -> 0.dp
                        PlayerChromeLayout.COMPACT -> 0.dp
                        PlayerChromeLayout.CINEMA -> 8.dp
                    },
                ),
        ) {
            val fraction = scrubFraction
                ?: if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val shownMs = scrubFraction?.let { (it * durationMs).toLong() } ?: positionMs
            if (minimal) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        formatPlayerTime(shownMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = PlayerInk,
                        fontWeight = FontWeight.Bold,
                    )
                    PlayerSeekSlider(
                        fraction = fraction,
                        durationMs = durationMs,
                        onFractionChange = {
                            scrubFraction = it
                            onInteract()
                        },
                        onFinished = {
                            scrubFraction?.let { onSeek((it * durationMs).toLong()) }
                            scrubFraction = null
                        },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "−${formatPlayerTime((durationMs - shownMs).coerceAtLeast(0L))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = PlayerMutedInk,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PlayerControlIconButton(
                            "Previous episode",
                            Icons.Default.SkipPrevious,
                            enabled = hasPrevious,
                            onClick = onPrevious,
                        )
                        PlayerControlIconButton(
                            "Next episode",
                            Icons.Default.SkipNext,
                            enabled = hasNext,
                            onClick = onNext,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, content = bottomRightIcons)
                }
            } else {
                PlayerSeekSlider(
                    fraction = fraction,
                    durationMs = durationMs,
                    onFractionChange = {
                        scrubFraction = it
                        onInteract()
                    },
                    onFinished = {
                        scrubFraction?.let { onSeek((it * durationMs).toLong()) }
                        scrubFraction = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatPlayerTime(shownMs),
                            style = MaterialTheme.typography.labelLarge,
                            color = PlayerInk,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "−${formatPlayerTime((durationMs - shownMs).coerceAtLeast(0L))}",
                            style = MaterialTheme.typography.labelMedium,
                            color = PlayerMutedInk,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, content = bottomRightIcons)
                }
            }
        }
    }
}

@Composable
private fun PlayerSeekSlider(
    fraction: Float,
    durationMs: Long,
    onFractionChange: (Float) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Slider(
        value = fraction,
        onValueChange = { if (durationMs > 0L) onFractionChange(it) },
        onValueChangeFinished = onFinished,
        enabled = durationMs > 0L,
        colors = playerSliderColors(),
        modifier = modifier
            .height(48.dp)
            .semantics { contentDescription = "Playback position" },
    )
}

@Composable
private fun PlayerChromeHeader(
    seriesTitle: String?,
    episodeTitle: String?,
    compact: Boolean,
    showMetadata: Boolean,
    onExitFullscreen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if ((!showMetadata || (seriesTitle.isNullOrBlank() && episodeTitle.isNullOrBlank())) && onExitFullscreen == null) {
        return
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onExitFullscreen != null) {
            PlayerControlIconButton(
                label = "Exit fullscreen",
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = onExitFullscreen,
            )
        } else if (showMetadata) {
            // WatchScreen owns the route-back button outside the player when it is inline. Keep
            // title context out from under that 48 dp target without duplicating its action here.
            Box(Modifier.size(48.dp))
        }
        if (showMetadata) Column(Modifier.weight(1f)) {
            if (!compact && !seriesTitle.isNullOrBlank()) {
                Text(
                    "NOW PLAYING",
                    color = PlayerMutedInk,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            val primary = if (compact) seriesTitle ?: episodeTitle else seriesTitle
            if (!primary.isNullOrBlank()) {
                Text(
                    primary,
                    color = PlayerInk,
                    style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!episodeTitle.isNullOrBlank() && episodeTitle != primary) {
                Text(
                    episodeTitle,
                    color = PlayerMutedInk,
                    style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun PlayerControlIconButton(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = label },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = PlayerInk.copy(alpha = if (enabled) 1f else 0.32f),
            modifier = Modifier.size(26.dp),
        )
    }
}

internal fun formatPlayerTime(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

@Composable
private fun playerSliderColors() = androidx.compose.material3.SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = PlayerInk,
    inactiveTrackColor = PlayerInk.copy(alpha = 0.26f),
    disabledThumbColor = PlayerMutedInk,
    disabledActiveTrackColor = PlayerMutedInk.copy(alpha = 0.4f),
    disabledInactiveTrackColor = PlayerMutedInk.copy(alpha = 0.2f),
)

@Preview(name = "Fullscreen landscape", widthDp = 840, heightDp = 390, backgroundColor = 0xFF141217, showBackground = true)
@Composable
private fun FullscreenPlayerControlsPreview() {
    PlayerControlsPreviewContent(onExitFullscreen = {})
}

@Preview(name = "Inline portrait", widthDp = 390, heightDp = 220, backgroundColor = 0xFF141217, showBackground = true)
@Preview(name = "Inline large text", widthDp = 400, heightDp = 225, fontScale = 2f, backgroundColor = 0xFF141217, showBackground = true)
@Preview(name = "Small inline 360", widthDp = 360, heightDp = 202, backgroundColor = 0xFF141217, showBackground = true)
@Preview(name = "Small inline 320", widthDp = 320, heightDp = 180, backgroundColor = 0xFF141217, showBackground = true)
@Composable
private fun InlinePlayerControlsPreview() {
    PlayerControlsPreviewContent(onExitFullscreen = null)
}

@Composable
private fun PlayerControlsPreviewContent(onExitFullscreen: (() -> Unit)?) {
    MaterialTheme {
        PlayerControlsScaffold(
            isPlaying = true,
            positionMs = 752_000L,
            durationMs = 1_416_000L,
            hasPrevious = true,
            hasNext = true,
            onPrevious = {},
            onRewind = {},
            onPlayPause = {},
            onForward = {},
            onNext = {},
            onSeek = {},
            seriesTitle = "Hunter × Hunter",
            episodeTitle = "Episode 12 · Last Test × Of × Resolve",
            onExitFullscreen = onExitFullscreen,
        )
    }
}
