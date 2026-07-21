package com.miruronative.ui.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EmbedCommandProgressPolicyTest {
    private val playbackKey = EmbedPlaybackKey(21, "allanime", "sub", 12.0, sourceGeneration = 8)
    private val video = EmbedVideoIdentity("https://cdn.example/video.m3u8?token=current", 2L)
    private val active = EmbedMediaIdentity(
        playbackKey = playbackKey,
        navigationGeneration = 40L,
        documentMediaId = "https://embed.example/watch?signature=current",
        videoIdentity = video,
    )
    private val command = EmbedCommand(
        id = 7L,
        navigationGeneration = active.navigationGeneration,
        kind = EmbedCommandKind.SEEK,
        issuedAtMs = 100L,
        mediaIdentity = video,
    )
    private val acknowledgement = EmbedCommandAcknowledgement(
        commandId = command.id,
        navigationGeneration = command.navigationGeneration,
        succeeded = true,
        positionMs = 420_000L,
        isPlaying = false,
        mediaIdentity = video,
    )
    private val resolution = EmbedCommandResolution.Confirmed(command, acknowledgement)

    @Test
    fun `paused seek on previously playing concrete video publishes exact acknowledged position`() {
        val progress = checkNotNull(
            confirmedProgressAfterEmbedSeek(
                resolution = resolution,
                activeMediaIdentity = active,
                currentPlaybackKey = playbackKey,
                confirmedMediaInstanceId = active.mediaInstanceId,
                durationMs = 1_440_000L,
            ),
        )

        assertEquals(active, progress.identity)
        assertEquals(420_000L, progress.positionMs)
        assertEquals(1_440_000L, progress.durationMs)
    }

    @Test
    fun `unplayed video cannot create history from a seek acknowledgement`() {
        assertNull(
            confirmedProgressAfterEmbedSeek(
                resolution = resolution,
                activeMediaIdentity = active,
                currentPlaybackKey = playbackKey,
                confirmedMediaInstanceId = null,
                durationMs = 1_440_000L,
            ),
        )
    }

    @Test
    fun `former video acknowledgement cannot publish after concrete replacement`() {
        val replacement = active.copy(
            videoIdentity = EmbedVideoIdentity("https://cdn.example/replacement.m3u8", 3L),
        )

        assertNull(
            confirmedProgressAfterEmbedSeek(
                resolution = resolution,
                activeMediaIdentity = replacement,
                currentPlaybackKey = playbackKey,
                confirmedMediaInstanceId = active.mediaInstanceId,
                durationMs = 1_440_000L,
            ),
        )
    }

    @Test
    fun `failed or non-seek command never publishes command progress`() {
        assertNull(
            confirmedProgressAfterEmbedSeek(
                resolution = EmbedCommandResolution.Confirmed(
                    command.copy(kind = EmbedCommandKind.TOGGLE_PLAYBACK),
                    acknowledgement,
                ),
                activeMediaIdentity = active,
                currentPlaybackKey = playbackKey,
                confirmedMediaInstanceId = active.mediaInstanceId,
                durationMs = 1_440_000L,
            ),
        )
        assertNull(
            confirmedProgressAfterEmbedSeek(
                resolution = EmbedCommandResolution.Confirmed(
                    command,
                    acknowledgement.copy(succeeded = false),
                ),
                activeMediaIdentity = active,
                currentPlaybackKey = playbackKey,
                confirmedMediaInstanceId = active.mediaInstanceId,
                durationMs = 1_440_000L,
            ),
        )
    }
}
