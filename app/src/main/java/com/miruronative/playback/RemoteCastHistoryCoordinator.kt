package com.miruronative.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import com.miruronative.ui.watch.isConfirmedFinalSeriesEpisode
import kotlinx.serialization.Serializable

/**
 * Everything the playback service needs to persist a native item without keeping a Watch screen
 * alive. [playbackId] identifies one concrete MediaItem installation; [generation] identifies the
 * logical episode request across quality/source changes.
 */
@Serializable
internal data class RemotePlaybackHistoryMetadata(
    val playbackId: String,
    val animeId: Int,
    val mediaId: String,
    val episodeNumber: Double,
    val generation: Int,
    val watchOwnerGeneration: Long,
    val seriesTitle: String,
    val coverUrl: String?,
    val episodeTitle: String?,
    val provider: String,
    val category: String,
    val navigationStreamUrl: String?,
    val totalEpisodes: Int?,
    val hasNextEpisode: Boolean,
    val nextEpisodeNumber: Double?,
) {
    val identity: RemotePlaybackHistoryIdentity
        get() = RemotePlaybackHistoryIdentity(
            playbackId = playbackId,
            animeId = animeId,
            mediaId = mediaId,
            episodeNumber = episodeNumber,
            generation = generation,
            watchOwnerGeneration = watchOwnerGeneration,
        )

    fun isValid(): Boolean =
        playbackId.isNotBlank() &&
            animeId > 0 &&
            mediaId.isNotBlank() &&
            episodeNumber.isFinite() &&
            generation >= 0 &&
            watchOwnerGeneration > 0L &&
            seriesTitle.isNotBlank() &&
            provider.isNotBlank() &&
            category.isNotBlank()
}

internal data class RemotePlaybackHistoryIdentity(
    val playbackId: String,
    val animeId: Int,
    val mediaId: String,
    val episodeNumber: Double,
    val generation: Int,
    val watchOwnerGeneration: Long,
)

/** Bounded sender-side lookup for the unique mediaId retained by DefaultMediaItemConverter. */
internal class RemotePlaybackHistoryRegistry(
    private val maxItems: Int = 16,
) {
    private val metadataByPlaybackId = LinkedHashMap<String, RemotePlaybackHistoryMetadata>()

    fun register(metadata: RemotePlaybackHistoryMetadata): Boolean {
        if (!metadata.isValid() || maxItems <= 0) return false
        if (metadataByPlaybackId[metadata.playbackId] == metadata) return false
        metadataByPlaybackId.remove(metadata.playbackId)
        metadataByPlaybackId[metadata.playbackId] = metadata
        while (metadataByPlaybackId.size > maxItems) {
            metadataByPlaybackId.remove(metadataByPlaybackId.entries.first().key)
        }
        return true
    }

    fun resolve(retainedMediaId: String): RemotePlaybackHistoryMetadata? =
        metadataByPlaybackId[retainedMediaId]?.takeIf { it.playbackId == retainedMediaId }

    fun snapshot(): List<RemotePlaybackHistoryMetadata> = metadataByPlaybackId.values.toList()

    fun clear() = metadataByPlaybackId.clear()
}

internal sealed interface RemoteHistoryWrite {
    val metadata: RemotePlaybackHistoryMetadata
    val positionMs: Long
    val durationMs: Long

    data class Confirmed(
        override val metadata: RemotePlaybackHistoryMetadata,
        override val positionMs: Long,
        override val durationMs: Long,
    ) : RemoteHistoryWrite

    data class Progress(
        override val metadata: RemotePlaybackHistoryMetadata,
        override val positionMs: Long,
        override val durationMs: Long,
        val durable: Boolean,
    ) : RemoteHistoryWrite

    data class Completion(
        override val metadata: RemotePlaybackHistoryMetadata,
        override val positionMs: Long,
        override val durationMs: Long,
        val completedFinalEpisode: Boolean,
    ) : RemoteHistoryWrite
}

/**
 * Identity and throttling policy for service-owned remote playback persistence.
 *
 * The coordinator deliberately returns write intents instead of touching Android storage. This
 * makes the exactly-once and stale-item gates testable, while the service remains responsible for
 * acknowledging a terminal write only after SharedPreferences commit succeeds.
 */
internal class RemoteCastHistoryCoordinator(
    private val progressIntervalMs: Long = 8_000L,
) {
    private var confirmedIdentity: RemotePlaybackHistoryIdentity? = null
    private var committedCompletionIdentity: RemotePlaybackHistoryIdentity? = null
    private var lastProgressWriteAtMs: Long? = null

    /** Seeds ownership during Media3's local-to-remote handoff without duplicating the UI write. */
    fun adoptConfirmedPlayback(
        metadata: RemotePlaybackHistoryMetadata?,
        elapsedRealtimeMs: Long,
    ): Boolean {
        if (metadata == null || !metadata.isValid()) return false
        val identity = metadata.identity
        if (confirmedIdentity == identity) return false
        committedCompletionIdentity = null
        confirmedIdentity = identity
        lastProgressWriteAtMs = elapsedRealtimeMs
        return true
    }

    fun sample(
        metadata: RemotePlaybackHistoryMetadata?,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        isRemote: Boolean,
        elapsedRealtimeMs: Long,
        force: Boolean = false,
    ): RemoteHistoryWrite? {
        if (!isRemote || metadata == null || !metadata.isValid()) return null
        val identity = metadata.identity
        val safePositionMs = positionMs.coerceAtLeast(0L)
        val safeDurationMs = durationMs.coerceAtLeast(0L)

        if (confirmedIdentity != identity) {
            committedCompletionIdentity = null
            lastProgressWriteAtMs = null
            if (!isPlaying) {
                confirmedIdentity = null
                return null
            }
            confirmedIdentity = identity
            lastProgressWriteAtMs = elapsedRealtimeMs
            return RemoteHistoryWrite.Confirmed(metadata, safePositionMs, safeDurationMs)
        }

        if (!force && !isPlaying) return null
        val lastWriteAt = lastProgressWriteAtMs
        if (!force && lastWriteAt != null && elapsedRealtimeMs - lastWriteAt < progressIntervalMs) {
            return null
        }
        lastProgressWriteAtMs = elapsedRealtimeMs
        return RemoteHistoryWrite.Progress(
            metadata = metadata,
            positionMs = safePositionMs,
            durationMs = safeDurationMs,
            durable = force,
        )
    }

    fun completion(
        metadata: RemotePlaybackHistoryMetadata?,
        reportedPositionMs: Long,
        durationMs: Long,
        isRemote: Boolean,
    ): RemoteHistoryWrite.Completion? {
        if (!isRemote || metadata == null || !metadata.isValid()) return null
        val identity = metadata.identity
        val terminalPositionIsCredible = durationMs > 0L &&
            reportedPositionMs >= (durationMs - minOf(5_000L, durationMs / 20L)).coerceAtLeast(0L)
        if (confirmedIdentity == null && terminalPositionIsCredible) {
            // A very short Cast item or a receiver reconnection can coalesce the isPlaying event.
            // STATE_ENDED plus a receiver position in the final 5% is still strong playback proof.
            confirmedIdentity = identity
        }
        if (
            identity != confirmedIdentity ||
            identity == committedCompletionIdentity ||
            reportedPositionMs < 0L ||
            durationMs <= 0L
        ) {
            return null
        }
        return RemoteHistoryWrite.Completion(
            metadata = metadata,
            positionMs = durationMs,
            durationMs = durationMs,
            completedFinalEpisode = isConfirmedFinalSeriesEpisode(
                episodeNumber = metadata.episodeNumber,
                totalEpisodes = metadata.totalEpisodes,
                hasNextEpisode = metadata.hasNextEpisode,
            ),
        )
    }

    fun acknowledgeCompletion(identity: RemotePlaybackHistoryIdentity): Boolean {
        if (identity != confirmedIdentity || identity == committedCompletionIdentity) return false
        committedCompletionIdentity = identity
        return true
    }

    /** Lets a failed first history insertion be retried by the next playing sample. */
    fun retryConfirmation(identity: RemotePlaybackHistoryIdentity): Boolean {
        if (identity != confirmedIdentity || identity == committedCompletionIdentity) return false
        confirmedIdentity = null
        lastProgressWriteAtMs = null
        return true
    }
}

internal fun Bundle.putRemotePlaybackHistoryMetadata(metadata: RemotePlaybackHistoryMetadata) {
    putString(EXTRA_HISTORY_PLAYBACK_ID, metadata.playbackId)
    putInt(EXTRA_HISTORY_ANIME_ID, metadata.animeId)
    putString(EXTRA_HISTORY_MEDIA_ID, metadata.mediaId)
    putDouble(EXTRA_HISTORY_EPISODE_NUMBER, metadata.episodeNumber)
    putInt(EXTRA_HISTORY_GENERATION, metadata.generation)
    putLong(EXTRA_HISTORY_WATCH_OWNER_GENERATION, metadata.watchOwnerGeneration)
    putString(EXTRA_HISTORY_SERIES_TITLE, metadata.seriesTitle)
    putString(EXTRA_HISTORY_COVER_URL, metadata.coverUrl)
    putString(EXTRA_HISTORY_EPISODE_TITLE, metadata.episodeTitle)
    putString(EXTRA_HISTORY_PROVIDER, metadata.provider)
    putString(EXTRA_HISTORY_CATEGORY, metadata.category)
    putString(EXTRA_HISTORY_NAVIGATION_STREAM_URL, metadata.navigationStreamUrl)
    putInt(EXTRA_HISTORY_TOTAL_EPISODES, metadata.totalEpisodes ?: 0)
    putBoolean(EXTRA_HISTORY_HAS_NEXT, metadata.hasNextEpisode)
    putDouble(EXTRA_HISTORY_NEXT_EPISODE, metadata.nextEpisodeNumber ?: Double.NaN)
}

internal fun MediaItem.remotePlaybackHistoryMetadataOrNull(): RemotePlaybackHistoryMetadata? {
    val extras = mediaMetadata.extras ?: return null
    val storedMediaId = extras.getString(EXTRA_HISTORY_MEDIA_ID) ?: return null
    val playbackId = extras.getString(EXTRA_HISTORY_PLAYBACK_ID) ?: return null
    if (playbackId != mediaId) return null
    if (
        !extras.containsKey(EXTRA_HISTORY_ANIME_ID) ||
        !extras.containsKey(EXTRA_HISTORY_EPISODE_NUMBER) ||
        !extras.containsKey(EXTRA_HISTORY_GENERATION) ||
        !extras.containsKey(EXTRA_HISTORY_WATCH_OWNER_GENERATION)
    ) {
        return null
    }
    return RemotePlaybackHistoryMetadata(
        playbackId = playbackId,
        animeId = extras.getInt(EXTRA_HISTORY_ANIME_ID),
        mediaId = storedMediaId,
        episodeNumber = extras.getDouble(EXTRA_HISTORY_EPISODE_NUMBER),
        generation = extras.getInt(EXTRA_HISTORY_GENERATION),
        watchOwnerGeneration = extras.getLong(EXTRA_HISTORY_WATCH_OWNER_GENERATION),
        seriesTitle = extras.getString(EXTRA_HISTORY_SERIES_TITLE) ?: return null,
        coverUrl = extras.getString(EXTRA_HISTORY_COVER_URL),
        episodeTitle = extras.getString(EXTRA_HISTORY_EPISODE_TITLE),
        provider = extras.getString(EXTRA_HISTORY_PROVIDER) ?: return null,
        category = extras.getString(EXTRA_HISTORY_CATEGORY) ?: return null,
        navigationStreamUrl = extras.getString(EXTRA_HISTORY_NAVIGATION_STREAM_URL),
        totalEpisodes = extras.getInt(EXTRA_HISTORY_TOTAL_EPISODES).takeIf { it > 0 },
        hasNextEpisode = extras.getBoolean(EXTRA_HISTORY_HAS_NEXT),
        nextEpisodeNumber = extras.getDouble(EXTRA_HISTORY_NEXT_EPISODE).takeIf(Double::isFinite),
    ).takeIf(RemotePlaybackHistoryMetadata::isValid)
}

private const val EXTRA_HISTORY_PLAYBACK_ID = "anilili.history.PLAYBACK_ID"
private const val EXTRA_HISTORY_ANIME_ID = "anilili.history.ANIME_ID"
private const val EXTRA_HISTORY_MEDIA_ID = "anilili.history.MEDIA_ID"
private const val EXTRA_HISTORY_EPISODE_NUMBER = "anilili.history.EPISODE_NUMBER"
private const val EXTRA_HISTORY_GENERATION = "anilili.history.GENERATION"
private const val EXTRA_HISTORY_WATCH_OWNER_GENERATION = "anilili.history.WATCH_OWNER_GENERATION"
private const val EXTRA_HISTORY_SERIES_TITLE = "anilili.history.SERIES_TITLE"
private const val EXTRA_HISTORY_COVER_URL = "anilili.history.COVER_URL"
private const val EXTRA_HISTORY_EPISODE_TITLE = "anilili.history.EPISODE_TITLE"
private const val EXTRA_HISTORY_PROVIDER = "anilili.history.PROVIDER"
private const val EXTRA_HISTORY_CATEGORY = "anilili.history.CATEGORY"
private const val EXTRA_HISTORY_NAVIGATION_STREAM_URL = "anilili.history.NAVIGATION_STREAM_URL"
private const val EXTRA_HISTORY_TOTAL_EPISODES = "anilili.history.TOTAL_EPISODES"
private const val EXTRA_HISTORY_HAS_NEXT = "anilili.history.HAS_NEXT"
private const val EXTRA_HISTORY_NEXT_EPISODE = "anilili.history.NEXT_EPISODE"
