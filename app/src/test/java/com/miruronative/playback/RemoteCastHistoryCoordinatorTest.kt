package com.miruronative.playback

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteCastHistoryCoordinatorTest {
    @Test
    fun `local playback never produces a service history write`() {
        val coordinator = RemoteCastHistoryCoordinator()

        val write = coordinator.sample(
            metadata = metadata(),
            positionMs = 2_000,
            durationMs = 20_000,
            isPlaying = true,
            isRemote = false,
            elapsedRealtimeMs = 1_000,
        )

        assertNull(write)
    }

    @Test
    fun `remote history begins only after the exact item is observed playing`() {
        val coordinator = RemoteCastHistoryCoordinator()
        val metadata = metadata()

        assertNull(
            coordinator.sample(
                metadata = metadata,
                positionMs = 0,
                durationMs = 20_000,
                isPlaying = false,
                isRemote = true,
                elapsedRealtimeMs = 1_000,
            ),
        )
        val write = coordinator.sample(
            metadata = metadata,
            positionMs = 1_500,
            durationMs = 20_000,
            isPlaying = true,
            isRemote = true,
            elapsedRealtimeMs = 2_000,
        ) as RemoteHistoryWrite.Confirmed

        assertEquals(metadata, write.metadata)
        assertEquals(1_500L, write.positionMs)
    }

    @Test
    fun `progress is throttled but a transition can force the exact remote position`() {
        val coordinator = RemoteCastHistoryCoordinator(progressIntervalMs = 8_000)
        val metadata = metadata()
        coordinator.sample(metadata, 1_000, 20_000, true, true, 1_000)

        assertNull(coordinator.sample(metadata, 2_000, 20_000, true, true, 8_999))
        val periodic = coordinator.sample(metadata, 3_000, 20_000, true, true, 9_000)
            as RemoteHistoryWrite.Progress
        assertEquals(3_000L, periodic.positionMs)

        val forced = coordinator.sample(
            metadata = metadata,
            positionMs = 3_500,
            durationMs = 20_000,
            isPlaying = false,
            isRemote = true,
            elapsedRealtimeMs = 9_001,
            force = true,
        ) as RemoteHistoryWrite.Progress
        assertEquals(3_500L, forced.positionMs)
        assertTrue(forced.durable)
    }

    @Test
    fun `completion rejects unconfirmed and stale media item identities`() {
        val coordinator = RemoteCastHistoryCoordinator()
        val first = metadata(playbackId = "first", mediaId = "https://cdn/first.m3u8")
        val second = metadata(playbackId = "second", mediaId = "https://cdn/second.m3u8")

        assertNull(coordinator.completion(first, 10_000, 20_000, isRemote = true))
        coordinator.sample(first, 1_000, 20_000, true, true, 1_000)
        coordinator.sample(second, 2_000, 20_000, true, true, 2_000)

        assertNull(coordinator.completion(first, 19_900, 20_000, isRemote = true))
        assertTrue(coordinator.completion(second, 19_900, 20_000, isRemote = true) != null)
    }

    @Test
    fun `terminal write becomes exactly once only after durable acknowledgement`() {
        val coordinator = RemoteCastHistoryCoordinator()
        val metadata = metadata()
        coordinator.sample(metadata, 1_000, 20_000, true, true, 1_000)

        val firstAttempt = coordinator.completion(metadata, 19_900, 20_000, isRemote = true)
        val retryAfterFailedDiskWrite = coordinator.completion(metadata, 19_900, 20_000, isRemote = true)

        assertTrue(firstAttempt != null)
        assertTrue(retryAfterFailedDiskWrite != null)
        assertTrue(coordinator.acknowledgeCompletion(metadata.identity))
        assertNull(coordinator.completion(metadata, 19_900, 20_000, isRemote = true))
        assertFalse(coordinator.acknowledgeCompletion(metadata.identity))
    }

    @Test
    fun `failed initial insertion can be retried without accepting a stale identity`() {
        val coordinator = RemoteCastHistoryCoordinator()
        val metadata = metadata()
        coordinator.sample(metadata, 1_000, 20_000, true, true, 1_000)

        assertTrue(coordinator.retryConfirmation(metadata.identity))
        val retry = coordinator.sample(metadata, 1_500, 20_000, true, true, 2_000)

        assertTrue(retry is RemoteHistoryWrite.Confirmed)
        assertFalse(
            coordinator.retryConfirmation(
                metadata(playbackId = "stale").identity,
            ),
        )
    }

    @Test
    fun `durable handoff confirmation is adopted only once`() {
        val coordinator = RemoteCastHistoryCoordinator()
        val metadata = metadata()

        assertTrue(coordinator.adoptConfirmedPlayback(metadata, elapsedRealtimeMs = 1_000))
        assertFalse(coordinator.adoptConfirmedPlayback(metadata, elapsedRealtimeMs = 2_000))
        assertTrue(
            coordinator.sample(
                metadata = metadata,
                positionMs = 3_000,
                durationMs = 20_000,
                isPlaying = false,
                isRemote = true,
                elapsedRealtimeMs = 2_001,
                force = true,
            ) is RemoteHistoryWrite.Progress,
        )
    }

    @Test
    fun `known final episode is durably retired but a navigable episode is not`() {
        val coordinator = RemoteCastHistoryCoordinator()
        val final = metadata(episodeNumber = 12.0, totalEpisodes = 12, hasNext = false)
        coordinator.sample(final, 1_000, 20_000, true, true, 1_000)

        val finalWrite = coordinator.completion(final, 19_900, 20_000, isRemote = true)

        assertTrue(finalWrite?.completedFinalEpisode == true)

        val next = metadata(
            playbackId = "next-item",
            mediaId = "https://cdn/next.m3u8",
            episodeNumber = 11.0,
            totalEpisodes = 12,
            hasNext = true,
        )
        coordinator.sample(next, 1_000, 20_000, true, true, 2_000)
        val nextWrite = coordinator.completion(next, 19_900, 20_000, isRemote = true)
        assertFalse(nextWrite?.completedFinalEpisode ?: true)
    }

    @Test
    fun `credible receiver terminal state recovers a coalesced playing event`() {
        val coordinator = RemoteCastHistoryCoordinator()
        val metadata = metadata()

        val completion = coordinator.completion(
            metadata = metadata,
            reportedPositionMs = 19_100,
            durationMs = 20_000,
            isRemote = true,
        )

        assertTrue(completion != null)
    }

    @Test
    fun `unconfirmed receiver position far from the end is not completion proof`() {
        val coordinator = RemoteCastHistoryCoordinator()

        assertNull(
            coordinator.completion(
                metadata = metadata(),
                reportedPositionMs = 10_000,
                durationMs = 20_000,
                isRemote = true,
            ),
        )
    }

    @Test
    fun `registry resolves Cast retained playback id when source URLs are reused`() {
        val registry = RemotePlaybackHistoryRegistry(maxItems = 4)
        val first = metadata(playbackId = "item-one", mediaId = "https://cdn/reused.m3u8")
        val second = metadata(playbackId = "item-two", mediaId = "https://cdn/reused.m3u8")

        assertTrue(registry.register(first))
        assertTrue(registry.register(second))

        assertEquals(first, registry.resolve("item-one"))
        assertEquals(second, registry.resolve("item-two"))
        assertNull(registry.resolve("https://cdn/reused.m3u8"))
    }

    @Test
    fun `registry snapshot survives durable serialization and service recreation`() {
        val original = RemotePlaybackHistoryRegistry(maxItems = 4)
        val first = metadata(playbackId = "item-one")
        val second = metadata(playbackId = "item-two", episodeNumber = 4.0)
        original.register(first)
        original.register(second)

        val encoded = Json.encodeToString(
            ListSerializer(RemotePlaybackHistoryMetadata.serializer()),
            original.snapshot(),
        )
        val restored = RemotePlaybackHistoryRegistry(maxItems = 4)
        Json.decodeFromString(
            ListSerializer(RemotePlaybackHistoryMetadata.serializer()),
            encoded,
        ).forEach(restored::register)

        assertEquals(first, restored.resolve("item-one"))
        assertEquals(second, restored.resolve("item-two"))
    }

    private fun metadata(
        playbackId: String = "playback-id",
        mediaId: String = "https://cdn/video.m3u8",
        episodeNumber: Double = 3.0,
        totalEpisodes: Int? = 12,
        hasNext: Boolean = true,
    ) = RemotePlaybackHistoryMetadata(
        playbackId = playbackId,
        animeId = 42,
        mediaId = mediaId,
        episodeNumber = episodeNumber,
        generation = 7,
        watchOwnerGeneration = 9L,
        seriesTitle = "Series",
        coverUrl = "https://cdn/cover.jpg",
        episodeTitle = "Episode title",
        provider = "provider",
        category = "sub",
        navigationStreamUrl = mediaId,
        totalEpisodes = totalEpisodes,
        hasNextEpisode = hasNext,
        nextEpisodeNumber = if (hasNext) episodeNumber + 1 else null,
    )
}
