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

internal fun playerChromeLayout(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float = 1f,
): PlayerChromeLayout = when {
    heightDp < 220f || widthDp < 340f -> PlayerChromeLayout.MINIMAL
    heightDp >= 280f &&
        widthDp >= 520f &&
        transportClearsFooter(PlayerChromeLayout.CINEMA, heightDp) -> PlayerChromeLayout.CINEMA
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
        transportOffsetDp = 0f,
        footerHeightDp = 112f,
    )
}

/** Mirrors the Compose geometry so small-size regressions are caught without screenshot tests. */
internal fun playerTransportClearsFooter(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float = 1f,
): Boolean {
    val layout = playerChromeLayout(widthDp, heightDp, fontScale)
    return transportClearsFooter(layout, heightDp)
}

private fun transportClearsFooter(layout: PlayerChromeLayout, heightDp: Float): Boolean {
    val metrics = playerChromeVerticalMetrics(layout)
    val transportBottom = heightDp / 2f + metrics.transportOffsetDp + metrics.transportSizeDp / 2f
    val footerTop = heightDp - metrics.footerHeightDp
    return transportBottom <= footerTop
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
