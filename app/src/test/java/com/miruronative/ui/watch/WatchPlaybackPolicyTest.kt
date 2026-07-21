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
    fun `pending teardown forces a surface free player mode`() {
        WatchPlayerMode.entries
            .filterNot { it == WatchPlayerMode.INACTIVE }
            .forEach { desired ->
                assertEquals(
                    WatchPlayerMode.INACTIVE,
                    playerModeForPlaybackTransition(
                        desiredMode = desired,
                        isResolving = false,
                        teardownGeneration = 42,
                    ),
                )
            }
    }

    @Test
    fun `stable playback keeps the desired player mode`() {
        WatchPlayerMode.entries.forEach { desired ->
            assertEquals(
                desired,
                playerModeForPlaybackTransition(
                    desiredMode = desired,
                    isResolving = false,
                    teardownGeneration = null,
                ),
            )
        }
    }

    @Test
    fun `teardown acknowledgement waits for inactive mode to be committed`() {
        assertFalse(
            canAcknowledgePlaybackTeardown(
                teardownGeneration = 42,
                requestedMode = WatchPlayerMode.INACTIVE,
                renderedMode = WatchPlayerMode.EMBED,
            ),
        )
        assertTrue(
            canAcknowledgePlaybackTeardown(
                teardownGeneration = 42,
                requestedMode = WatchPlayerMode.INACTIVE,
                renderedMode = WatchPlayerMode.INACTIVE,
            ),
        )
    }

    @Test
    fun `route start cannot expose retained success before replacement state is observed`() {
        assertFalse(
            canAuthorizeStartedRoute(
                previousStateWasSuccess = true,
                replacementStateObserved = false,
            ),
        )
        assertTrue(
            canAuthorizeStartedRoute(
                previousStateWasSuccess = true,
                replacementStateObserved = true,
            ),
        )
        assertTrue(
            canAuthorizeStartedRoute(
                previousStateWasSuccess = false,
                replacementStateObserved = false,
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
