package com.miruronative.ui.watch

import com.miruronative.data.model.Category

/** Identifies the logical player that emitted an episode-navigation command. */
data class PlaybackNavigationIdentity(
    val animeId: Int,
    val episodeNumber: Double,
    val provider: String,
    val category: Category,
    val playbackGeneration: Int,
    val streamUrl: String?,
)

internal fun WatchData.playbackNavigationIdentity(): PlaybackNavigationIdentity =
    PlaybackNavigationIdentity(
        animeId = anilistId,
        episodeNumber = current.number,
        provider = provider,
        category = category,
        playbackGeneration = playbackGeneration,
        streamUrl = chosenStream?.url,
    )

internal fun isCurrentPlaybackNavigation(
    requestedBy: PlaybackNavigationIdentity,
    current: PlaybackNavigationIdentity,
): Boolean = requestedBy == current

internal data class EpisodeResolutionKey(
    val animeId: Int,
    val episodeNumber: Double,
    val preferredProvider: String,
    val category: Category,
    val excludedProviders: Set<String>,
)

internal fun isDuplicateEpisodeResolution(
    resolveActive: Boolean,
    pending: EpisodeResolutionKey?,
    requested: EpisodeResolutionKey,
): Boolean = resolveActive && pending == requested
