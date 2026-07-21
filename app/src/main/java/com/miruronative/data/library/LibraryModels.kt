package com.miruronative.data.library

import kotlinx.serialization.Serializable

/** One history record per anime: the last episode watched plus an optional continuation target. */
@Serializable
data class HistoryEntry(
    val anilistId: Int,
    val title: String,
    val cover: String?,
    val episodeNumber: Double,
    val episodeTitle: String? = null,
    val provider: String,
    val category: String,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /**
     * Episode to open after a naturally completed, non-final episode. The default keeps history
     * written by older app versions compatible and represents ordinary in-progress playback.
     */
    val continuationEpisodeNumber: Double? = null,
    /** Completed final episodes stay in viewing history but are hidden from Continue Watching. */
    val completed: Boolean = false,
    val updatedAt: Long = 0,
) {
    private val activeContinuationEpisodeNumber: Double?
        get() = continuationEpisodeNumber?.takeIf {
            !completed && it.isFinite() && it != episodeNumber
        }

    val progressFraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val episodeLabel: String
        get() = episodeNumber.toEpisodeLabel()

    /** Episode and position represented by Continue Watching entry points. */
    val continueEpisodeNumber: Double
        get() = activeContinuationEpisodeNumber ?: episodeNumber

    val continueEpisodeLabel: String
        get() = continueEpisodeNumber.toEpisodeLabel()

    val continuePositionMs: Long
        get() = if (activeContinuationEpisodeNumber != null) 0L else positionMs

    val continueDurationMs: Long
        get() = if (activeContinuationEpisodeNumber != null) 0L else durationMs

    val continueProgressFraction: Float
        get() = if (activeContinuationEpisodeNumber != null) 0f else progressFraction

    val hasContinuationTarget: Boolean
        get() = activeContinuationEpisodeNumber != null

    val belongsInContinueWatching: Boolean
        get() = !completed

    fun resumePositionFor(episode: Double): Long? = when {
        completed -> null
        activeContinuationEpisodeNumber == episode -> 0L
        activeContinuationEpisodeNumber == null && episodeNumber == episode -> positionMs
        else -> null
    }
}

private fun Double.toEpisodeLabel(): String =
    if (this % 1.0 == 0.0) toInt().toString() else toString()

/** A saved series the user wants to watch. */
@Serializable
data class WatchlistEntry(
    val anilistId: Int,
    val title: String,
    val cover: String?,
    val format: String? = null,
    val averageScore: Int? = null,
    val addedAt: Long = 0,
)

internal fun mergeWatchlistEntries(
    local: List<WatchlistEntry>,
    fromAniList: List<WatchlistEntry>,
    addedAt: Long = System.currentTimeMillis(),
): List<WatchlistEntry> {
    if (fromAniList.isEmpty()) return local
    val remoteById = fromAniList.associateBy { it.anilistId }
    val localIds = local.mapTo(mutableSetOf()) { it.anilistId }
    return buildList {
        local.forEach { saved ->
            val remote = remoteById[saved.anilistId]
            add(remote?.copy(addedAt = saved.addedAt) ?: saved)
        }
        fromAniList.forEach { remote ->
            if (remote.anilistId !in localIds) add(remote.copy(addedAt = addedAt))
        }
    }.distinctBy { it.anilistId }
}
