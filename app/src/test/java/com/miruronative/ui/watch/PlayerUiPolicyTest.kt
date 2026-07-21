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
    fun `skip action has a reserved non-overlapping slot at supported player geometries`() {
        val cases = listOf(
            Triple(320f, 180f, PlayerChromeLayout.MINIMAL),
            Triple(390f, 220f, PlayerChromeLayout.COMPACT),
            Triple(520f, 280f, PlayerChromeLayout.COMPACT),
            Triple(840f, 390f, PlayerChromeLayout.CINEMA),
        )

        cases.forEach { (widthDp, heightDp, expectedLayout) ->
            assertEquals(expectedLayout, playerChromeLayout(widthDp, heightDp))
            assertEquals(true, playerTransportClearsFooter(widthDp, heightDp))
            assertEquals(true, playerActionClearsReservedChrome(widthDp, heightDp))
        }
        assertEquals(
            PlayerChromeActionPresentation.ICON_ONLY,
            playerChromeActionPresentation(widthDp = 320f, heightDp = 180f),
        )
        listOf(390f to 220f, 520f to 280f, 840f to 390f).forEach { (widthDp, heightDp) ->
            assertEquals(
                PlayerChromeActionPresentation.LABELED,
                playerChromeActionPresentation(widthDp, heightDp),
            )
        }
    }

    @Test
    fun `large cinema metadata falls back to compact until it clears transport`() {
        assertEquals(
            PlayerChromeLayout.COMPACT,
            playerChromeLayout(widthDp = 520f, heightDp = 294f, fontScale = 2f),
        )
        assertEquals(
            PlayerChromeLayout.CINEMA,
            playerChromeLayout(widthDp = 840f, heightDp = 390f, fontScale = 2f),
        )
    }

    @Test
    fun `minimal action keeps full accessible labels while larger chrome shows them`() {
        val labels = listOf(
            "Skip Intro",
            "Skip Outro",
            "Skip Mixed Opening",
            "Skip Mixed Ending",
            "Skip Recap",
            "Next Episode",
        )

        labels.forEach { label ->
            val minimal = playerChromeActionContent(label, PlayerChromeActionPresentation.ICON_ONLY)
            assertEquals(null, minimal.visibleLabel)
            assertEquals(label, minimal.contentDescription)

            val labeled = playerChromeActionContent(label, PlayerChromeActionPresentation.LABELED)
            assertEquals(label.uppercase(), labeled.visibleLabel)
            assertEquals(label, labeled.contentDescription)
        }
    }

    @Test
    fun `contextual action stays composed when transport chrome auto hides`() {
        assertEquals(true, shouldComposePlayerChrome(showChrome = true, hasPrimaryAction = false))
        assertEquals(true, shouldComposePlayerChrome(showChrome = true, hasPrimaryAction = true))
        assertEquals(true, shouldComposePlayerChrome(showChrome = false, hasPrimaryAction = true))
        assertEquals(false, shouldComposePlayerChrome(showChrome = false, hasPrimaryAction = false))
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
