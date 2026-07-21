package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerModeHandoffTest {
    @Test
    fun `native to embed stops service before render`() {
        assertEquals(
            PlayerModeHandoffAction.STOP_NATIVE_THEN_RENDER,
            playerModeHandoffAction(WatchPlayerMode.NATIVE, WatchPlayerMode.EMBED),
        )
    }

    @Test
    fun `native to terminal state stops service before render`() {
        assertEquals(
            PlayerModeHandoffAction.STOP_NATIVE_THEN_RENDER,
            playerModeHandoffAction(WatchPlayerMode.NATIVE, WatchPlayerMode.INACTIVE),
        )
    }

    @Test
    fun `first embed render clears service playback defensively`() {
        assertEquals(
            PlayerModeHandoffAction.STOP_NATIVE_THEN_RENDER,
            playerModeHandoffAction(null, WatchPlayerMode.EMBED),
        )
    }

    @Test
    fun `first terminal render clears service playback defensively`() {
        assertEquals(
            PlayerModeHandoffAction.STOP_NATIVE_THEN_RENDER,
            playerModeHandoffAction(null, WatchPlayerMode.INACTIVE),
        )
    }

    @Test
    fun `native owner can render without clearing its service`() {
        assertEquals(
            PlayerModeHandoffAction.RENDER,
            playerModeHandoffAction(WatchPlayerMode.INACTIVE, WatchPlayerMode.NATIVE),
        )
    }

    @Test
    fun `stable mode does not repeat handoff`() {
        WatchPlayerMode.entries.forEach { mode ->
            assertEquals(
                PlayerModeHandoffAction.RENDER,
                playerModeHandoffAction(mode, mode),
            )
        }
    }
}
