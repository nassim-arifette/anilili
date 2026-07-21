package com.miruronative.ui.watch

/**
 * The touch chrome deliberately has two densities. An inline portrait player is only about
 * 200 dp tall and must keep the video readable; a fullscreen landscape player can afford title
 * context and a more generous transport cluster.
 */
internal enum class PlayerChromeLayout {
    COMPACT,
    CINEMA,
}

internal fun playerChromeLayout(widthDp: Float, heightDp: Float): PlayerChromeLayout =
    if (heightDp < 280f || widthDp < 520f) PlayerChromeLayout.COMPACT else PlayerChromeLayout.CINEMA

internal enum class PlayerSettingsSection(val title: String) {
    PLAYBACK("Playback"),
    VIDEO("Video"),
    AUDIO("Audio"),
    SUBTITLES("Subtitles"),
    ADVANCED("Advanced"),
}

internal data class PlayerSettingsAvailability(
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
    add(PlayerSettingsSection.PLAYBACK)
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
