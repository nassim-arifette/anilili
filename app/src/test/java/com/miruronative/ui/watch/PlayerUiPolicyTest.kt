package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerUiPolicyTest {
    @Test
    fun `short inline players retain metadata at normal font scale`() {
        assertEquals(
            PlayerChromeLayout.COMPACT,
            playerChromeLayout(widthDp = 390f, heightDp = 220f, fontScale = 1f),
        )
        assertEquals(
            PlayerChromeLayout.COMPACT,
            playerChromeLayout(widthDp = 400f, heightDp = 225f, fontScale = 1f),
        )
        assertEquals(true, playerTransportClearsFooter(widthDp = 390f, heightDp = 220f))
    }

    @Test
    fun `large text removes metadata before it can overlap inline transport`() {
        assertEquals(
            PlayerChromeLayout.MINIMAL,
            playerChromeLayout(widthDp = 390f, heightDp = 220f, fontScale = 2f),
        )
        assertEquals(
            PlayerChromeLayout.MINIMAL,
            playerChromeLayout(widthDp = 400f, heightDp = 225f, fontScale = 2f),
        )
        assertEquals(
            true,
            playerTransportClearsFooter(widthDp = 400f, heightDp = 225f, fontScale = 2f),
        )
    }

    @Test
    fun `fullscreen landscape player keeps cinema chrome with large text`() {
        assertEquals(
            PlayerChromeLayout.CINEMA,
            playerChromeLayout(widthDp = 840f, heightDp = 390f, fontScale = 1f),
        )
        assertEquals(
            PlayerChromeLayout.CINEMA,
            playerChromeLayout(widthDp = 840f, heightDp = 390f, fontScale = 2f),
        )
    }

    @Test
    fun `wide but shallow player stays compact`() {
        assertEquals(PlayerChromeLayout.COMPACT, playerChromeLayout(widthDp = 840f, heightDp = 240f))
    }

    @Test
    fun `cinema chrome starts only after its transport clears the footer`() {
        assertEquals(PlayerChromeLayout.COMPACT, playerChromeLayout(widthDp = 520f, heightDp = 280f))
        assertEquals(PlayerChromeLayout.COMPACT, playerChromeLayout(widthDp = 520f, heightDp = 293f))
        assertEquals(PlayerChromeLayout.CINEMA, playerChromeLayout(widthDp = 520f, heightDp = 294f))
        assertEquals(true, playerTransportClearsFooter(widthDp = 520f, heightDp = 280f))
        assertEquals(true, playerTransportClearsFooter(widthDp = 520f, heightDp = 293f))
        assertEquals(true, playerTransportClearsFooter(widthDp = 520f, heightDp = 294f))
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
