package com.miruronative.ui.watch

/**
 * The touch chrome deliberately has three densities. The smallest inline players cannot fit a
 * header, five-button transport and two-row footer without overlapping touch targets, while a
 * fullscreen landscape player can afford title context and a more generous transport cluster.
 */
internal enum class PlayerChromeLayout {
    MINIMAL,
    COMPACT,
    CINEMA,
}

/**
 * The skip/next action shares the header row with metadata on layouts that can show a readable
 * label. Minimal inline chrome reserves one 48 dp corner target instead: its title metadata is
 * already hidden and the icon stays horizontally clear of the centred transport cluster.
 */
internal enum class PlayerChromeActionPresentation {
    ICON_ONLY,
    LABELED,
}

internal fun playerChromeActionPresentation(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float = 1f,
): PlayerChromeActionPresentation = when (playerChromeLayout(widthDp, heightDp, fontScale)) {
    PlayerChromeLayout.MINIMAL -> PlayerChromeActionPresentation.ICON_ONLY
    PlayerChromeLayout.COMPACT,
    PlayerChromeLayout.CINEMA,
    -> PlayerChromeActionPresentation.LABELED
}

internal data class PlayerChromeActionContent(
    val visibleLabel: String?,
    val contentDescription: String,
)

/** Minimal chrome may hide only the glyph's text; assistive technology always gets the full label. */
internal fun playerChromeActionContent(
    label: String,
    presentation: PlayerChromeActionPresentation,
): PlayerChromeActionContent = PlayerChromeActionContent(
    visibleLabel = label.uppercase().takeIf { presentation == PlayerChromeActionPresentation.LABELED },
    contentDescription = label,
)

/** Keeps the contextual action mounted when transport chrome auto-hides. */
internal fun shouldComposePlayerChrome(
    showChrome: Boolean,
    hasPrimaryAction: Boolean,
): Boolean = showChrome || hasPrimaryAction

internal fun playerChromeLayout(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float = 1f,
): PlayerChromeLayout = when {
    heightDp < 220f || widthDp < 340f -> PlayerChromeLayout.MINIMAL
    heightDp >= 280f && widthDp >= 520f -> PlayerChromeLayout.CINEMA
    !compactMetadataClearsTransport(heightDp, fontScale) -> PlayerChromeLayout.MINIMAL
    else -> PlayerChromeLayout.COMPACT
}

/**
 * Compact chrome can show two metadata lines above a centred transport. At large Android font
 * scales those lines grow while the 48 dp navigation target does not, so geometry alone is not
 * enough to decide whether the header fits. Keep a small visual gap instead of letting the title
 * collide with playback controls.
 */
private fun compactMetadataClearsTransport(heightDp: Float, fontScale: Float): Boolean {
    val effectiveFontScale = if (fontScale.isFinite()) fontScale.coerceAtLeast(1f) else 1f
    val metadataHeightDp = 36f * effectiveFontScale
    val headerBottomDp = 4f + maxOf(48f, metadataHeightDp)
    val compactMetrics = playerChromeVerticalMetrics(PlayerChromeLayout.COMPACT)
    val transportTopDp =
        heightDp / 2f + compactMetrics.transportOffsetDp - compactMetrics.transportSizeDp / 2f
    return headerBottomDp + 4f <= transportTopDp
}

internal data class PlayerChromeVerticalMetrics(
    val transportSizeDp: Float,
    val transportOffsetDp: Float,
    val footerHeightDp: Float,
)

internal fun playerChromeVerticalMetrics(layout: PlayerChromeLayout): PlayerChromeVerticalMetrics = when (layout) {
    PlayerChromeLayout.MINIMAL -> PlayerChromeVerticalMetrics(
        transportSizeDp = 52f,
        transportOffsetDp = -36f,
        footerHeightDp = 96f,
    )
    PlayerChromeLayout.COMPACT -> PlayerChromeVerticalMetrics(
        transportSizeDp = 58f,
        transportOffsetDp = -18f,
        footerHeightDp = 96f,
    )
    PlayerChromeLayout.CINEMA -> PlayerChromeVerticalMetrics(
        transportSizeDp = 70f,
        // At the smallest cinema geometry (520 x 280), centring the 70 dp play target at y=140
        // intruded into the 112 dp timeline/footer reservation. Eight dp upward keeps both clear.
        transportOffsetDp = -8f,
        footerHeightDp = 112f,
    )
}

/** Mirrors the Compose geometry so small-size regressions are caught without screenshot tests. */
internal fun playerTransportClearsFooter(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float = 1f,
): Boolean {
    val metrics = playerChromeVerticalMetrics(playerChromeLayout(widthDp, heightDp, fontScale))
    val transportBottom = heightDp / 2f + metrics.transportOffsetDp + metrics.transportSizeDp / 2f
    val footerTop = heightDp - metrics.footerHeightDp
    return transportBottom <= footerTop
}

/**
 * Mirrors the action/header placement in [PlayerControlsScaffold]. Labeled actions are measured as
 * part of the header row, so Row allocation keeps them disjoint from metadata. Minimal chrome has
 * vertical overlap with transport by design; this check proves that its 48 dp end-corner slot is
 * horizontally disjoint from the three-button transport cluster and vertically above the footer.
 */
internal fun playerActionClearsReservedChrome(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float = 1f,
): Boolean {
    val layout = playerChromeLayout(widthDp, heightDp, fontScale)
    val metrics = playerChromeVerticalMetrics(layout)
    val horizontalPadding = when (layout) {
        PlayerChromeLayout.MINIMAL -> 8f
        PlayerChromeLayout.COMPACT -> 12f
        PlayerChromeLayout.CINEMA -> 24f
    }
    val headerTop = if (layout == PlayerChromeLayout.CINEMA) 12f else 4f
    val actionBottom = headerTop + 48f
    val footerTop = heightDp - metrics.footerHeightDp
    if (actionBottom > footerTop) return false

    val transportTop = heightDp / 2f + metrics.transportOffsetDp - metrics.transportSizeDp / 2f
    if (actionBottom <= transportTop) return true

    // Only minimal chrome is allowed to rely on horizontal separation. Its cluster contains the
    // 48 dp rewind/forward targets, a 52 dp play target, and two 2 dp gaps.
    if (layout != PlayerChromeLayout.MINIMAL) return false
    val actionStart = widthDp - horizontalPadding - 48f
    val transportWidth = 48f + 2f + metrics.transportSizeDp + 2f + 48f
    val transportEnd = widthDp / 2f + transportWidth / 2f
    return transportEnd <= actionStart
}

internal enum class PlayerSettingsSection(val title: String) {
    PLAYBACK("Playback"),
    VIDEO("Video"),
    AUDIO("Audio"),
    SUBTITLES("Subtitles"),
    ADVANCED("Advanced"),
}

internal data class PlayerSettingsAvailability(
    val hasPlaybackAutomation: Boolean,
    val hasSpeed: Boolean,
    val hasQuality: Boolean,
    val hasContentScale: Boolean,
    val hasAudioTracks: Boolean,
    val hasSubtitles: Boolean,
    val hasSubtitleDelay: Boolean,
    val hasCaptionAppearance: Boolean,
    val hasPictureInPicture: Boolean,
)

/** Only exposes groups containing controls the active player can really honour. */
internal fun availablePlayerSettingsSections(
    availability: PlayerSettingsAvailability,
): List<PlayerSettingsSection> = buildList {
    if (availability.hasPlaybackAutomation || availability.hasSpeed) add(PlayerSettingsSection.PLAYBACK)
    if (availability.hasQuality || availability.hasContentScale) add(PlayerSettingsSection.VIDEO)
    // Device volume is real for every playback mode, even when the provider has no audio tracks.
    add(PlayerSettingsSection.AUDIO)
    if (
        availability.hasSubtitles ||
        availability.hasSubtitleDelay ||
        availability.hasCaptionAppearance
    ) {
        add(PlayerSettingsSection.SUBTITLES)
    }
    if (availability.hasPictureInPicture) add(PlayerSettingsSection.ADVANCED)
}
