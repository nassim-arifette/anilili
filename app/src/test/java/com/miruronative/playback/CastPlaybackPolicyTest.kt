package com.miruronative.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastPlaybackPolicyTest {
    @Test
    fun `remote disconnect resumes locally while player surface is active`() {
        assertEquals(
            CastTransferDirective.PRESERVE_PLAY_STATE,
            castTransferDirective(
                sourceRoute = PlaybackRoute.REMOTE,
                targetRoute = PlaybackRoute.LOCAL,
                hasLocalPlaybackOwner = true,
            ),
        )
    }

    @Test
    fun `late remote disconnect transfers locally in paused state after teardown`() {
        assertEquals(
            CastTransferDirective.TRANSFER_LOCAL_PAUSED,
            castTransferDirective(
                sourceRoute = PlaybackRoute.REMOTE,
                targetRoute = PlaybackRoute.LOCAL,
                hasLocalPlaybackOwner = false,
            ),
        )
    }

    @Test
    fun `connecting to Cast preserves play state without a local owner`() {
        assertEquals(
            CastTransferDirective.PRESERVE_PLAY_STATE,
            castTransferDirective(
                sourceRoute = PlaybackRoute.LOCAL,
                targetRoute = PlaybackRoute.REMOTE,
                hasLocalPlaybackOwner = false,
            ),
        )
    }

    @Test
    fun `overlapping player surfaces retain ownership until both are released`() {
        val owners = LocalPlaybackOwnerRegistry()
        val first = owners.acquire()
        val second = owners.acquire()

        assertTrue(owners.hasOwner())
        assertTrue(owners.release(first))
        assertTrue(owners.hasOwner())
        assertTrue(owners.release(second))
        assertFalse(owners.hasOwner())
    }

    @Test
    fun `releasing the same player surface twice is harmless`() {
        val owners = LocalPlaybackOwnerRegistry()
        val token = owners.acquire()

        assertTrue(owners.release(token))
        assertFalse(owners.release(token))
        assertFalse(owners.hasOwner())
    }
}
