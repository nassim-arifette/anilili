package com.miruronative.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPresentationGateTest {

    @Test
    fun `presentation is accepted only once`() {
        val gate = PlayerPresentationGate()
        val presentation = presentation(generation = 1, episode = 1.0)

        assertTrue(gate.accept(presentation, presentation, resolutionPending = false))
        assertFalse(gate.accept(presentation, presentation, resolutionPending = false))
    }

    @Test
    fun `presentation rejected during resolution can be accepted when resolution completes`() {
        val gate = PlayerPresentationGate()
        val presentation = presentation(generation = 2, episode = 2.0)

        assertFalse(gate.accept(presentation, presentation, resolutionPending = true))
        assertTrue(gate.accept(presentation, presentation, resolutionPending = false))
    }

    @Test
    fun `stale player cannot record history for the current episode`() {
        val gate = PlayerPresentationGate()
        val oldPlayer = presentation(generation = 3, episode = 3.0)
        val currentPlayer = presentation(generation = 4, episode = 4.0)

        assertFalse(gate.accept(oldPlayer, currentPlayer, resolutionPending = false))
        assertTrue(gate.accept(currentPlayer, currentPlayer, resolutionPending = false))
    }

    private fun presentation(generation: Int, episode: Double) = PlayerPresentation(
        generation = generation,
        anilistId = 100,
        episodeNumber = episode,
        provider = "bonk",
        category = "sub",
        streamUrl = "https://cdn.example/$episode/master.m3u8",
    )
}
