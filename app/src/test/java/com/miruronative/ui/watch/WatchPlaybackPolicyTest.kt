package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchPlaybackPolicyTest {
    @Test
    fun `loading state stops playback left by a previous native surface`() {
        assertTrue(
            shouldStopNativePlaybackForWatchState(
                isSuccess = false,
                hasChosenStream = false,
                usesNativePlayer = false,
                isWebFallback = false,
            ),
        )
    }

    @Test
    fun `error state stops playback even if an old stream was retained elsewhere`() {
        assertTrue(
            shouldStopNativePlaybackForWatchState(
                isSuccess = false,
                hasChosenStream = true,
                usesNativePlayer = true,
                isWebFallback = false,
            ),
        )
    }

    @Test
    fun `successful state without a source stops playback`() {
        assertTrue(
            shouldStopNativePlaybackForWatchState(
                isSuccess = true,
                hasChosenStream = false,
                usesNativePlayer = false,
                isWebFallback = false,
            ),
        )
    }

    @Test
    fun `successful state with a native source keeps its player active`() {
        assertFalse(
            shouldStopNativePlaybackForWatchState(
                isSuccess = true,
                hasChosenStream = true,
                usesNativePlayer = true,
                isWebFallback = false,
            ),
        )
    }

    @Test
    fun `embed source stops playback owned by the native service`() {
        assertTrue(
            shouldStopNativePlaybackForWatchState(
                isSuccess = true,
                hasChosenStream = true,
                usesNativePlayer = false,
                isWebFallback = false,
            ),
        )
    }

    @Test
    fun `web fallback stops native playback even while native state is retained`() {
        assertTrue(
            shouldStopNativePlaybackForWatchState(
                isSuccess = true,
                hasChosenStream = true,
                usesNativePlayer = true,
                isWebFallback = true,
            ),
        )
    }

    @Test
    fun `surface lease accepts work while active`() {
        val lease = NativePlaybackSurfaceLease()
        var calls = 0

        assertTrue(lease.runIfActive { calls++ })
        assertEquals(1, calls)
    }

    @Test
    fun `released surface lease rejects late controller and prepare work`() {
        val lease = NativePlaybackSurfaceLease()
        var calls = 0

        lease.release()
        lease.release()

        assertFalse(lease.runIfActive { calls++ })
        assertFalse(lease.runIfActive { calls++ })
        assertEquals(0, calls)
    }
}
