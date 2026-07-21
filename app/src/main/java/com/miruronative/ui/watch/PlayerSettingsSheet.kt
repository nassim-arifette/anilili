package com.miruronative.ui.watch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.miruronative.playback.SubtitleDelay
import com.miruronative.ui.adaptive.LocalAppDeviceProfile
import com.miruronative.ui.adaptive.focusHighlight
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal enum class PlayerContentScale(val label: String) {
    FIT("Fit"),
    CROP("Crop"),
    FILL("Fill"),
}

internal data class PlayerQualityOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

private val SheetColor = Color(0xFF151417)
private val SheetMuted = Color(0xFFAAA6AF)

/**
 * Capability-aware settings for both native and embed playback.
 *
 * Phones first see five understandable groups and drill into one at a time. This keeps the first
 * sheet calm while preserving every existing control. TV uses the same navigation inside its
 * in-window panel, retaining D-pad focus instead of opening a separate dialog window.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PlayerSettingsSheet(
    onDismiss: () -> Unit,
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    speed: Float? = null,
    onSpeedChange: (Float) -> Unit = {},
    qualityOptions: List<PlayerQualityOption> = emptyList(),
    subtitleOptions: List<PlayerQualityOption> = emptyList(),
    audioOptions: List<PlayerQualityOption> = emptyList(),
    contentScale: PlayerContentScale? = null,
    onContentScaleChange: (PlayerContentScale) -> Unit = {},
    onCaptionAppearance: (() -> Unit)? = null,
    autoSkip: Boolean? = null,
    onAutoSkipChange: (Boolean) -> Unit = {},
    subtitleDelayMs: Long? = null,
    onSubtitleDelayChange: (Long) -> Unit = {},
    onEnterPip: (() -> Unit)? = null,
) {
    val isTv = LocalAppDeviceProfile.current.isTv
    val headerFocusRequester = remember { FocusRequester() }
    var selectedSection by remember { mutableStateOf<PlayerSettingsSection?>(null) }
    val availability = PlayerSettingsAvailability(
        hasSpeed = speed != null,
        hasQuality = qualityOptions.isNotEmpty(),
        hasContentScale = contentScale != null,
        hasAudioTracks = audioOptions.size > 1,
        hasSubtitles = subtitleOptions.isNotEmpty(),
        hasSubtitleDelay = subtitleDelayMs != null,
        hasCaptionAppearance = onCaptionAppearance != null,
        hasPictureInPicture = onEnterPip != null,
    )
    val sections = remember(availability) { availablePlayerSettingsSections(availability) }
    val title = selectedSection?.title ?: "Player settings"
    val navigateBack = { selectedSection = null }

    val content: @Composable () -> Unit = {
        SettingsHeader(
            title = title,
            canNavigateBack = selectedSection != null,
            onBack = navigateBack,
            onClose = onDismiss,
            initialFocusRequester = headerFocusRequester.takeIf { isTv },
        )
        Spacer(Modifier.height(8.dp))
        if (selectedSection == null) {
            SettingsLanding(
                sections = sections,
                autoplay = autoplay,
                speed = speed,
                qualityOptions = qualityOptions,
                audioOptions = audioOptions,
                subtitleOptions = subtitleOptions,
                contentScale = contentScale,
                onOpen = { selectedSection = it },
            )
        } else {
            SettingsSectionContent(
                section = selectedSection!!,
                autoplay = autoplay,
                onAutoplayChange = onAutoplayChange,
                speed = speed,
                onSpeedChange = onSpeedChange,
                qualityOptions = qualityOptions,
                subtitleOptions = subtitleOptions,
                audioOptions = audioOptions,
                contentScale = contentScale,
                onContentScaleChange = onContentScaleChange,
                onCaptionAppearance = onCaptionAppearance,
                autoSkip = autoSkip,
                onAutoSkipChange = onAutoSkipChange,
                subtitleDelayMs = subtitleDelayMs,
                onSubtitleDelayChange = onSubtitleDelayChange,
                onEnterPip = onEnterPip,
            )
        }
    }

    BackHandler(enabled = selectedSection != null, onBack = navigateBack)
    if (isTv) {
        TvSettingsPanel(
            title = title,
            onBack = if (selectedSection == null) onDismiss else navigateBack,
            initialFocusRequester = headerFocusRequester,
            content = content,
        )
        return
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetColor,
        dragHandle = { BottomSheetDefaults.DragHandle(color = SheetMuted.copy(alpha = 0.7f)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsHeader(
    title: String,
    canNavigateBack: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    initialFocusRequester: FocusRequester? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (canNavigateBack) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .then(initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
            Text(
                title,
                color = Color(0xFFF3F0F5),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .then(
                    if (!canNavigateBack) {
                        initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
                    } else {
                        Modifier
                    },
                )
                .size(48.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close settings", tint = SheetMuted)
        }
    }
}

@Composable
private fun SettingsLanding(
    sections: List<PlayerSettingsSection>,
    autoplay: Boolean,
    speed: Float?,
    qualityOptions: List<PlayerQualityOption>,
    audioOptions: List<PlayerQualityOption>,
    subtitleOptions: List<PlayerQualityOption>,
    contentScale: PlayerContentScale?,
    onOpen: (PlayerSettingsSection) -> Unit,
) {
    Text(
        "Only controls supported by this source are shown.",
        color = SheetMuted,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    sections.forEachIndexed { index, section ->
        SettingsLandingRow(
            section = section,
            summary = sectionSummary(
                section = section,
                autoplay = autoplay,
                speed = speed,
                qualityOptions = qualityOptions,
                audioOptions = audioOptions,
                subtitleOptions = subtitleOptions,
                contentScale = contentScale,
            ),
            onClick = { onOpen(section) },
        )
        if (index != sections.lastIndex) HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
    }
}

@Composable
private fun SettingsLandingRow(
    section: PlayerSettingsSection,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .focusHighlight(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = section.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(section.title, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                color = SheetMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = SheetMuted,
        )
    }
}

private fun PlayerSettingsSection.icon(): ImageVector = when (this) {
    PlayerSettingsSection.PLAYBACK -> Icons.Default.PlayCircle
    PlayerSettingsSection.VIDEO -> Icons.Default.AspectRatio
    PlayerSettingsSection.AUDIO -> Icons.AutoMirrored.Filled.VolumeUp
    PlayerSettingsSection.SUBTITLES -> Icons.Default.ClosedCaption
    PlayerSettingsSection.ADVANCED -> Icons.Default.Tune
}

private fun sectionSummary(
    section: PlayerSettingsSection,
    autoplay: Boolean,
    speed: Float?,
    qualityOptions: List<PlayerQualityOption>,
    audioOptions: List<PlayerQualityOption>,
    subtitleOptions: List<PlayerQualityOption>,
    contentScale: PlayerContentScale?,
): String = when (section) {
    PlayerSettingsSection.PLAYBACK -> buildList {
        add(if (autoplay) "Auto next on" else "Auto next off")
        speed?.let { add(it.formatPlaybackSpeed()) }
    }.joinToString(" · ")
    PlayerSettingsSection.VIDEO -> listOfNotNull(
        qualityOptions.firstOrNull { it.selected }?.label,
        contentScale?.label,
    ).ifEmpty { listOf("Source defaults") }.joinToString(" · ")
    PlayerSettingsSection.AUDIO ->
        audioOptions.firstOrNull { it.selected }?.label ?: "Device volume"
    PlayerSettingsSection.SUBTITLES ->
        subtitleOptions.firstOrNull { it.selected }?.label ?: "Off or source defaults"
    PlayerSettingsSection.ADVANCED -> "Picture-in-Picture"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsSectionContent(
    section: PlayerSettingsSection,
    autoplay: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    speed: Float?,
    onSpeedChange: (Float) -> Unit,
    qualityOptions: List<PlayerQualityOption>,
    subtitleOptions: List<PlayerQualityOption>,
    audioOptions: List<PlayerQualityOption>,
    contentScale: PlayerContentScale?,
    onContentScaleChange: (PlayerContentScale) -> Unit,
    onCaptionAppearance: (() -> Unit)?,
    autoSkip: Boolean?,
    onAutoSkipChange: (Boolean) -> Unit,
    subtitleDelayMs: Long?,
    onSubtitleDelayChange: (Long) -> Unit,
    onEnterPip: (() -> Unit)?,
) {
    when (section) {
        PlayerSettingsSection.PLAYBACK -> {
            ToggleRow("Auto-play next episode", autoplay, onAutoplayChange)
            autoSkip?.let {
                ToggleRow(
                    label = "Auto-skip standard intro/outro",
                    checked = it,
                    onCheckedChange = onAutoSkipChange,
                    supportingText = "Mixed themes and recaps stay manual.",
                )
            }
            speed?.let { current ->
                SectionLabel("Speed")
                SpeedSlider(current, onSpeedChange)
            }
        }
        PlayerSettingsSection.VIDEO -> {
            if (qualityOptions.isNotEmpty()) {
                SectionLabel("Quality")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    qualityOptions.forEach { ChoiceChip(it.label, it.selected, it.onSelect) }
                }
            }
            contentScale?.let { current ->
                SectionLabel("Video fit")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlayerContentScale.entries.forEach { scale ->
                        ChoiceChip(scale.label, scale == current) { onContentScaleChange(scale) }
                    }
                }
            }
        }
        PlayerSettingsSection.AUDIO -> {
            SectionLabel("Volume")
            MediaVolumeSlider(modifier = Modifier.fillMaxWidth(), showPercentLabel = true)
            if (audioOptions.size > 1) {
                SectionLabel("Track")
                audioOptions.forEach { TrackRow(it.label, it.selected, it.onSelect) }
            }
        }
        PlayerSettingsSection.SUBTITLES -> {
            if (subtitleOptions.isNotEmpty()) {
                SectionLabel("Track")
                subtitleOptions.forEach { TrackRow(it.label, it.selected, it.onSelect) }
            }
            subtitleDelayMs?.let { current ->
                SectionLabel("Timing")
                SubtitleDelayRow(current, onSubtitleDelayChange)
            }
            onCaptionAppearance?.let { open ->
                SectionLabel("Appearance")
                ClickableRow("Caption style", open)
            }
        }
        PlayerSettingsSection.ADVANCED -> {
            onEnterPip?.let {
                ClickableRow(
                    label = "Picture-in-Picture",
                    onClick = it,
                    supportingText = "Keep watching over other apps.",
                    icon = Icons.Default.PictureInPictureAlt,
                )
            }
        }
    }
}

/** TV presentation stays in the player window so D-pad and TalkBack focus cannot escape. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun TvSettingsPanel(
    title: String,
    onBack: () -> Unit,
    initialFocusRequester: FocusRequester,
    content: @Composable () -> Unit,
) {
    BackHandler(onBack = onBack)
    LaunchedEffect(title) {
        delay(64)
        runCatching { initialFocusRequester.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxSize()
            .zIndex(10f)
            .background(Color.Black.copy(alpha = 0.58f)),
    ) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(440.dp)
                .background(SheetColor)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .verticalScroll(rememberScrollState())
                .semantics { paneTitle = "Player settings" }
                .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 32.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SpeedSlider(speed: Float, onSpeedChange: (Float) -> Unit) {
    val speeds = PlaybackSpeeds
    val index = remember(speed) {
        speeds.indexOfFirst { abs(it - speed) < 0.001f }
            .takeIf { it >= 0 }
            ?: speeds.indexOfFirst { it == 1f }.coerceAtLeast(0)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Speed, contentDescription = null, tint = SheetMuted)
        Slider(
            value = index.toFloat(),
            onValueChange = { onSpeedChange(speeds[it.roundToInt().coerceIn(0, speeds.lastIndex)]) },
            valueRange = 0f..speeds.lastIndex.toFloat(),
            steps = (speeds.size - 2).coerceAtLeast(0),
            colors = whiteSliderColors(),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .semantics { contentDescription = "Playback speed" }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val next = when (event.key) {
                        Key.DirectionLeft -> index - 1
                        Key.DirectionRight -> index + 1
                        else -> return@onPreviewKeyEvent false
                    }
                    if (next in speeds.indices) {
                        onSpeedChange(speeds[next])
                        true
                    } else {
                        false
                    }
                },
        )
        Text(
            speed.formatPlaybackSpeed(),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.widthIn(min = 44.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale.US),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .focusHighlight(RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.08f))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .focusHighlight(RoundedCornerShape(8.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            style = MaterialTheme.typography.bodyLarge,
        )
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supportingText: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .focusHighlight(RoundedCornerShape(8.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            supportingText?.let {
                Text(it, color = SheetMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubtitleDelayRow(delayMs: Long, onChange: (Long) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ChoiceChip("−0.25s", false) { onChange(delayMs - SubtitleDelay.STEP_MS) }
        Text(
            if (delayMs == 0L) "0.00 s" else String.format(Locale.US, "%+.2f s", delayMs / 1000.0),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        ChoiceChip("+0.25s", false) { onChange(delayMs + SubtitleDelay.STEP_MS) }
        if (delayMs != 0L) ChoiceChip("Reset", false) { onChange(0L) }
    }
    Text(
        when {
            delayMs == 0L -> "Subtitles use the provider timing."
            SubtitleDelay.isAutomatic -> "Measured for this stream's encode."
            delayMs > 0L -> "Subtitles are held back."
            else -> "Subtitles run ahead."
        },
        color = SheetMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ClickableRow(
    label: String,
    onClick: () -> Unit,
    supportingText: String? = null,
    icon: ImageVector? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusHighlight(RoundedCornerShape(8.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            supportingText?.let { Text(it, color = SheetMuted, style = MaterialTheme.typography.bodySmall) }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = SheetMuted)
    }
}

@Composable
internal fun whiteSliderColors() = SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.primary,
    activeTrackColor = Color.White,
    inactiveTrackColor = Color.White.copy(alpha = 0.24f),
)
