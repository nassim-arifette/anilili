package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedTvControlPolicyTest {
    @Test
    fun `same-origin managed video keeps app transport and consumes remote input`() {
        val policy = embedTvControlPolicy(
            playerOwnsRemote = true,
            managedControlsDeclared = true,
            bridgeAvailable = true,
            providerControlsMode = false,
            automationSupported = true,
            settingsSupported = true,
            fullscreenSupported = true,
        )

        assertEquals(EmbedTvRemoteOwner.APP_TRANSPORT, policy.remoteOwner)
        assertTrue(policy.showsAppTransport)
        assertFalse(policy.showsProviderHandoff)
        assertTrue(policy.showsSettings)
        assertTrue(policy.showsFullscreen)
        assertTrue(policy.allowsAppPlaybackCommands)
        assertTrue(policy.allowsSeeking)
        assertTrue(policy.allowsPlaybackAutomation)
        assertTrue(policy.consumesDirectionalOrSelectForApp)
        assertFalse(policy.passesDirectionalOrSelectToProvider)
        assertFalse(policy.recoversAppUiOnBack)
    }

    @Test
    fun `cross-origin video exposes only honest app actions before handoff`() {
        val policy = embedTvControlPolicy(
            playerOwnsRemote = true,
            managedControlsDeclared = true,
            bridgeAvailable = false,
            providerControlsMode = false,
            automationSupported = true,
            settingsSupported = true,
            fullscreenSupported = true,
        )

        assertEquals(EmbedTvRemoteOwner.APP_PROVIDER_HANDOFF, policy.remoteOwner)
        assertFalse(policy.showsAppTransport)
        assertTrue(policy.showsProviderHandoff)
        assertTrue(policy.showsSettings)
        assertTrue(policy.showsFullscreen)
        assertFalse(policy.allowsAppPlaybackCommands)
        assertFalse(policy.allowsSeeking)
        assertFalse(policy.allowsPlaybackAutomation)
        assertTrue(policy.consumesDirectionalOrSelectForApp)
        assertFalse(policy.passesDirectionalOrSelectToProvider)
        assertFalse(policy.recoversAppUiOnBack)
    }

    @Test
    fun `provider receives remote after handoff and Back recovers app UI`() {
        val policy = embedTvControlPolicy(
            playerOwnsRemote = true,
            managedControlsDeclared = true,
            bridgeAvailable = false,
            providerControlsMode = true,
            automationSupported = true,
            settingsSupported = true,
            fullscreenSupported = true,
        )

        assertEquals(EmbedTvRemoteOwner.PROVIDER, policy.remoteOwner)
        assertFalse(policy.showsAppTransport)
        assertFalse(policy.showsProviderHandoff)
        assertFalse(policy.showsSettings)
        assertFalse(policy.showsFullscreen)
        assertFalse(policy.allowsAppPlaybackCommands)
        assertFalse(policy.allowsSeeking)
        assertFalse(policy.allowsPlaybackAutomation)
        assertFalse(policy.consumesDirectionalOrSelectForApp)
        assertTrue(policy.passesDirectionalOrSelectToProvider)
        assertTrue(policy.recoversAppUiOnBack)
    }

    @Test
    fun `inactive inline player leaves focus and input with parent screen`() {
        val policy = embedTvControlPolicy(
            playerOwnsRemote = false,
            managedControlsDeclared = true,
            bridgeAvailable = false,
            providerControlsMode = false,
            automationSupported = true,
            settingsSupported = true,
            fullscreenSupported = true,
        )

        assertEquals(EmbedTvRemoteOwner.PARENT, policy.remoteOwner)
        assertFalse(policy.showsAppTransport)
        assertFalse(policy.showsProviderHandoff)
        assertFalse(policy.showsSettings)
        assertFalse(policy.showsFullscreen)
        assertFalse(policy.consumesDirectionalOrSelectForApp)
        assertFalse(policy.passesDirectionalOrSelectToProvider)
        assertFalse(policy.recoversAppUiOnBack)
    }

    @Test
    fun `unmanaged page receives input without installing an app Back trap`() {
        val policy = embedTvControlPolicy(
            playerOwnsRemote = true,
            managedControlsDeclared = false,
            bridgeAvailable = false,
            providerControlsMode = false,
            automationSupported = false,
            settingsSupported = false,
            fullscreenSupported = false,
        )

        assertEquals(EmbedTvRemoteOwner.PROVIDER, policy.remoteOwner)
        assertTrue(policy.passesDirectionalOrSelectToProvider)
        assertFalse(policy.recoversAppUiOnBack)
    }
}
