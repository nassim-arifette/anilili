package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbedCommandCoordinatorTest {
    @Test
    fun `matching acknowledgement confirms command exactly once`() {
        val coordinator = EmbedCommandCoordinator(timeoutMs = 100)
        val command = coordinator.issue(7, EmbedCommandKind.SEEK, nowMs = 10)
        val ack = EmbedCommandAcknowledgement(command.id, 7, true, 42_000, true)

        val resolution = coordinator.acknowledge(ack, nowMs = 20)

        assertTrue(resolution is EmbedCommandResolution.Confirmed)
        assertEquals(EmbedCommandResolution.Ignored, coordinator.acknowledge(ack, nowMs = 21))
    }

    @Test
    fun `wrong navigation acknowledgement cannot consume pending command`() {
        val coordinator = EmbedCommandCoordinator(timeoutMs = 100)
        val command = coordinator.issue(7, EmbedCommandKind.TOGGLE_PLAYBACK, nowMs = 10)

        assertEquals(
            EmbedCommandResolution.Ignored,
            coordinator.acknowledge(
                EmbedCommandAcknowledgement(command.id, 8, true, 0, true),
                nowMs = 20,
            ),
        )
        assertTrue(
            coordinator.acknowledge(
                EmbedCommandAcknowledgement(command.id, 7, true, 0, true),
                nowMs = 21,
            ) is EmbedCommandResolution.Confirmed,
        )
    }

    @Test
    fun `timeout removes command and late acknowledgement is ignored`() {
        val coordinator = EmbedCommandCoordinator(timeoutMs = 100)
        val command = coordinator.issue(7, EmbedCommandKind.SEEK, nowMs = 10)

        assertEquals(EmbedCommandResolution.Ignored, coordinator.timeout(command.id, nowMs = 109))
        assertTrue(coordinator.timeout(command.id, nowMs = 110) is EmbedCommandResolution.TimedOut)
        assertEquals(
            EmbedCommandResolution.Ignored,
            coordinator.acknowledge(
                EmbedCommandAcknowledgement(command.id, 7, true, 42_000, true),
                nowMs = 111,
            ),
        )
    }

    @Test
    fun `late acknowledgement resolves as timeout instead of confirmation`() {
        val coordinator = EmbedCommandCoordinator(timeoutMs = 100)
        val command = coordinator.issue(7, EmbedCommandKind.SEEK, nowMs = 10)

        assertTrue(
            coordinator.acknowledge(
                EmbedCommandAcknowledgement(command.id, 7, true, 42_000, true),
                nowMs = 111,
            ) is EmbedCommandResolution.TimedOut,
        )
    }

    @Test
    fun `newer command of same kind supersedes older acknowledgement`() {
        val coordinator = EmbedCommandCoordinator(timeoutMs = 100)
        val first = coordinator.issue(7, EmbedCommandKind.SEEK, nowMs = 10)
        val second = coordinator.issue(7, EmbedCommandKind.SEEK, nowMs = 11)

        assertEquals(
            EmbedCommandResolution.Ignored,
            coordinator.acknowledge(
                EmbedCommandAcknowledgement(first.id, 7, true, 10_000, true),
                nowMs = 20,
            ),
        )
        assertTrue(
            coordinator.acknowledge(
                EmbedCommandAcknowledgement(second.id, 7, true, 20_000, true),
                nowMs = 21,
            ) is EmbedCommandResolution.Confirmed,
        )
    }

    @Test
    fun `acknowledgement from replaced media is rejected`() {
        val coordinator = EmbedCommandCoordinator(timeoutMs = 100)
        val episodeA = EmbedVideoIdentity("episode-a?signed=secret", generation = 1)
        val prerollB = EmbedVideoIdentity("preroll-b", generation = 2)
        val command = coordinator.issue(
            navigationGeneration = 7,
            kind = EmbedCommandKind.SEEK,
            nowMs = 10,
            mediaIdentity = episodeA,
        )

        assertTrue(
            coordinator.acknowledge(
                EmbedCommandAcknowledgement(
                    command.id,
                    7,
                    true,
                    42_000,
                    true,
                    mediaIdentity = prerollB,
                ),
                nowMs = 20,
                activeMediaIdentity = prerollB,
            ) is EmbedCommandResolution.Rejected,
        )
        assertTrue(command.toString().contains("<redacted>"))
        assertTrue(!command.toString().contains("signed=secret"))
    }

    @Test
    fun `same raw media id from an older generation cannot acknowledge after replacement`() {
        val coordinator = EmbedCommandCoordinator(timeoutMs = 100)
        val oldVideo = EmbedVideoIdentity("blob:https://embed.example/video", generation = 1)
        val replacement = oldVideo.copy(generation = 2)
        val command = coordinator.issue(
            navigationGeneration = 7,
            kind = EmbedCommandKind.TOGGLE_PLAYBACK,
            nowMs = 10,
            mediaIdentity = oldVideo,
        )

        assertTrue(
            coordinator.acknowledge(
                EmbedCommandAcknowledgement(
                    command.id,
                    7,
                    true,
                    42_000,
                    true,
                    mediaIdentity = oldVideo,
                ),
                nowMs = 20,
                activeMediaIdentity = replacement,
            ) is EmbedCommandResolution.Rejected,
        )
    }
}
