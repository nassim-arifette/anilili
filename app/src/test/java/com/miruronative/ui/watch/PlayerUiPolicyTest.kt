package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerUiPolicyTest {
    @Test
    fun `inline portrait player uses compact chrome`() {
        assertEquals(PlayerChromeLayout.COMPACT, playerChromeLayout(widthDp = 390f, heightDp = 220f))
        assertEquals(true, playerTransportClearsFooter(widthDp = 390f, heightDp = 220f))
    }

    @Test
    fun `fullscreen landscape player uses cinema chrome`() {
        assertEquals(PlayerChromeLayout.CINEMA, playerChromeLayout(widthDp = 840f, heightDp = 390f))
    }

    @Test
    fun `wide but shallow player stays compact`() {
        assertEquals(PlayerChromeLayout.COMPACT, playerChromeLayout(widthDp = 840f, heightDp = 240f))
    }

    @Test
    fun `small inline players use minimal non-overlapping chrome`() {
        assertEquals(PlayerChromeLayout.MINIMAL, playerChromeLayout(widthDp = 320f, heightDp = 180f))
        assertEquals(PlayerChromeLayout.MINIMAL, playerChromeLayout(widthDp = 360f, heightDp = 202f))
        assertEquals(true, playerTransportClearsFooter(widthDp = 320f, heightDp = 180f))
        assertEquals(true, playerTransportClearsFooter(widthDp = 360f, heightDp = 202f))
    }

    @Test
    fun `settings only expose capabilities supported by active player`() {
        val sections = availablePlayerSettingsSections(
            PlayerSettingsAvailability(
                hasPlaybackAutomation = false,
                hasSpeed = false,
                hasQuality = false,
                hasContentScale = false,
                hasAudioTracks = false,
                hasSubtitles = false,
                hasSubtitleDelay = false,
                hasCaptionAppearance = false,
                hasPictureInPicture = false,
            ),
        )

        assertEquals(
            listOf(PlayerSettingsSection.AUDIO),
            sections,
        )
    }

    @Test
    fun `native capabilities produce stable progressive settings order`() {
        val sections = availablePlayerSettingsSections(
            PlayerSettingsAvailability(
                hasPlaybackAutomation = true,
                hasSpeed = true,
                hasQuality = true,
                hasContentScale = true,
                hasAudioTracks = true,
                hasSubtitles = true,
                hasSubtitleDelay = true,
                hasCaptionAppearance = true,
                hasPictureInPicture = true,
            ),
        )

        assertEquals(PlayerSettingsSection.entries, sections)
    }

    @Test
    fun `caption appearance keeps subtitle group discoverable without tracks`() {
        val sections = availablePlayerSettingsSections(
            PlayerSettingsAvailability(
                hasPlaybackAutomation = true,
                hasSpeed = true,
                hasQuality = false,
                hasContentScale = false,
                hasAudioTracks = false,
                hasSubtitles = false,
                hasSubtitleDelay = false,
                hasCaptionAppearance = true,
                hasPictureInPicture = false,
            ),
        )

        assertEquals(
            listOf(
                PlayerSettingsSection.PLAYBACK,
                PlayerSettingsSection.AUDIO,
                PlayerSettingsSection.SUBTITLES,
            ),
            sections,
        )
    }

    @Test
    fun `speed remains available when provider automation is unavailable`() {
        val sections = availablePlayerSettingsSections(
            PlayerSettingsAvailability(
                hasPlaybackAutomation = false,
                hasSpeed = true,
                hasQuality = false,
                hasContentScale = false,
                hasAudioTracks = false,
                hasSubtitles = false,
                hasSubtitleDelay = false,
                hasCaptionAppearance = false,
                hasPictureInPicture = false,
            ),
        )

        assertEquals(
            listOf(PlayerSettingsSection.PLAYBACK, PlayerSettingsSection.AUDIO),
            sections,
        )
    }
}
